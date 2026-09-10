// Negative tests 7-9 and 13: the status/event invariant, the append-only audit trail,
// internal visibility, and the evidence contract on Levererat.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, deleteDoc, doc, getDocs, query, serverTimestamp, setDoc, updateDoc, where,
  writeBatch,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeEnv, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeEnv(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

const CASE = 'cases/case-alice';

/** The only shape the rules accept: both halves of the transition in one commit. */
function transition(db, { from, to, eventId = 'ev1', event = {}, caseFields = {} }) {
  const batch = writeBatch(db);
  batch.update(doc(db, CASE), {
    statusCache: to,
    lastStatusEventId: eventId,
    updatedAt: serverTimestamp(),
    ...caseFields,
  });
  batch.set(doc(db, `${CASE}/events/${eventId}`), {
    type: 'status_change',
    fromStatus: from,
    toStatus: to,
    actorUid: 'lars',
    visibility: 'public',
    createdAt: serverTimestamp(),
    ...event,
  });
  return batch.commit();
}

async function forceStatus(env, statusCache) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), CASE), { statusCache });
  });
}

describe('a status change and its event are one commit or nothing', () => {
  test('the paired write succeeds', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(transition(db, { from: 'Mottaget', to: 'Under granskning' }));
  });

  test('changing status without the event is denied (test 7)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, CASE), {
      statusCache: 'Under granskning',
      lastStatusEventId: 'ev1',
      updatedAt: serverTimestamp(),
    }));
  });

  test('a standalone status_change event is denied (test 7)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(setDoc(doc(db, `${CASE}/events/ev1`), {
      type: 'status_change',
      fromStatus: 'Mottaget',
      toStatus: 'Under granskning',
      actorUid: 'lars',
      visibility: 'public',
      createdAt: serverTimestamp(),
    }));
  });

  test('an event describing a different transition is denied (test 7)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, {
      from: 'Mottaget',
      to: 'Under granskning',
      event: { toStatus: 'Levererat' },
    }));
  });

  test('a forged actorUid is denied (test 7)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, {
      from: 'Mottaget',
      to: 'Under granskning',
      event: { actorUid: 'alice' },
    }));
  });

  test('a client-chosen createdAt is denied (test 7)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, {
      from: 'Mottaget',
      to: 'Under granskning',
      event: { createdAt: new Date(2020, 0, 1) },
    }));
  });

  test('a transition outside the map is denied (test 7)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, { from: 'Mottaget', to: 'Levererat' }));
  });

  test('the case owner cannot move status at all', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(transition(db, {
      from: 'Mottaget',
      to: 'Under granskning',
      event: { actorUid: 'alice' },
    }));
  });
});

describe('the audit trail is append-only', () => {
  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, `${CASE}/events/ev-public`), {
        type: 'status_change',
        fromStatus: 'Mottaget',
        toStatus: 'Under granskning',
        actorUid: 'lars',
        visibility: 'public',
        createdAt: new Date(),
      });
      await setDoc(doc(db, `${CASE}/events/ev-internal`), {
        type: 'note',
        actorUid: 'lars',
        visibility: 'internal',
        body: 'draft reply',
        createdAt: new Date(),
      });
    });
  });

  test('even the writer cannot edit or delete an event (test 8)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, `${CASE}/events/ev-public`), { toStatus: 'Levererat' }));
    await assertFails(deleteDoc(doc(db, `${CASE}/events/ev-public`)));
  });

  test('the owner sees public events only, and never by unconstrained query (test 9)', async () => {
    const db = as(env, 'alice').firestore();
    const events = collection(db, `${CASE}/events`);
    await assertFails(getDocs(events));
    await assertSucceeds(getDocs(query(events, where('visibility', '==', 'public'))));
    await assertFails(getDocs(query(events, where('visibility', '==', 'internal'))));
  });

  test('a reader-admin sees internal events', async () => {
    const db = as(env, 'reader').firestore();
    await assertSucceeds(getDocs(collection(db, `${CASE}/events`)));
  });
});

describe('Levererat carries evidence or it does not happen', () => {
  const EVIDENCE = {
    releaseTag: 'v20260907',
    versionCode: 2026090700,
    distributedAt: new Date(Date.now() - 60_000),
    verifiedBy: 'lars',
  };

  beforeEach(async () => { await forceStatus(env, 'Pågår'); });

  test('a complete evidence block is accepted (test 13)', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(transition(db, {
      from: 'Pågår', to: 'Levererat', event: { evidence: EVIDENCE },
    }));
  });

  test('no evidence at all is denied (test 13)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, { from: 'Pågår', to: 'Levererat' }));
  });

  test('an incomplete evidence block is denied (test 13)', async () => {
    const db = as(env, 'lars').firestore();
    const { distributedAt, ...partial } = EVIDENCE;
    await assertFails(transition(db, {
      from: 'Pågår', to: 'Levererat', event: { evidence: partial },
    }));
  });

  test('verifiedBy must be the writing account (test 13)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, {
      from: 'Pågår',
      to: 'Levererat',
      event: { evidence: { ...EVIDENCE, verifiedBy: 'alice' } },
    }));
  });

  test('a distributedAt in the future is denied (test 13)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(transition(db, {
      from: 'Pågår',
      to: 'Levererat',
      event: { evidence: { ...EVIDENCE, distributedAt: new Date(Date.now() + 86_400_000) } },
    }));
  });

  test('Levererat is terminal', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(transition(db, {
      from: 'Pågår', to: 'Levererat', event: { evidence: EVIDENCE },
    }));
    await assertFails(transition(db, {
      from: 'Levererat', to: 'Parkerat', eventId: 'ev2',
    }));
  });
});
