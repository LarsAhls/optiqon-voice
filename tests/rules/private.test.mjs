// The collections that carry no cross-document invariant and were therefore easy to leave
// untested: an account's own private subtrees, invites, news and deletion requests.
//
// They matter for the same reason the noisier rules do. Every one of them is storage and
// bandwidth somebody pays for, and every one of them is reachable from the Firestore API by
// anyone holding a token — the app is not in the way.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, updateDoc,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeEnv, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeEnv(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

describe('a private subtree still belongs to an approved account', () => {
  test('an approved account writes and reads its own sync and reads documents', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'users/alice/sync/state'), { cursor: 12 }));
    await assertSucceeds(setDoc(doc(db, 'users/alice/reads/news-1'), { readAt: serverTimestamp() }));
    await assertSucceeds(getDoc(doc(db, 'users/alice/sync/state')));
  });

  test('a pending account cannot write to its own subtree', async () => {
    // Signed in is not let in: without this, an applicant nobody has approved can fill a
    // collection at the project's expense while waiting.
    const db = as(env, 'pat').firestore();
    await assertFails(setDoc(doc(db, 'users/pat/sync/state'), { cursor: 12 }));
    await assertFails(setDoc(doc(db, 'users/pat/reads/news-1'), { readAt: serverTimestamp() }));
  });

  test('a revoked account cannot write to its own subtree', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'users/alice'), { status: 'revoked' });
    });
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'users/alice/sync/state'), { cursor: 12 }));
  });

  test('nobody reaches another account subtree, approved or not', async () => {
    const db = as(env, 'bob').firestore();
    await assertFails(setDoc(doc(db, 'users/alice/sync/state'), { cursor: 12 }));
    await assertFails(getDoc(doc(db, 'users/alice/sync/state')));
    await assertFails(getDocs(collection(db, 'users/alice/reads')));
  });

  test('an account can still read what it wrote after being revoked', async () => {
    // Reading is left open deliberately: a revoked tester keeps access to their own record,
    // and locking them out of it would delete nothing while helping nobody.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/alice/sync/state'), { cursor: 12 });
      await updateDoc(doc(ctx.firestore(), 'users/alice'), { status: 'revoked' });
    });
    const db = as(env, 'alice').firestore();
    await assertSucceeds(getDoc(doc(db, 'users/alice/sync/state')));
  });
});

describe('invites are readable by their recipient and writable by nobody else', () => {
  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'invites/newbie@example.com'), { invitedBy: 'lars' });
    });
  });

  test('the invited address may read its own invite', async () => {
    const db = as(env, 'newbie').firestore();
    await assertSucceeds(getDoc(doc(db, 'invites/newbie@example.com')));
  });

  test('somebody else may not read it, and nobody may list them', async () => {
    const db = as(env, 'bob').firestore();
    await assertFails(getDoc(doc(db, 'invites/newbie@example.com')));
    await assertFails(getDocs(collection(db, 'invites')));
  });

  test('a tester cannot invite themselves', async () => {
    const db = as(env, 'newbie').firestore();
    await assertFails(setDoc(doc(db, 'invites/newbie@example.com'), { invitedBy: 'newbie' }));
  });

  test('a reader-admin may list invites but not write one', async () => {
    const db = as(env, 'reader').firestore();
    await assertSucceeds(getDocs(collection(db, 'invites')));
    await assertFails(setDoc(doc(db, 'invites/other@example.com'), { invitedBy: 'reader' }));
  });

  test('even the writer cannot delete an invite', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(setDoc(doc(db, 'invites/other@example.com'), { invitedBy: 'lars' }));
    await assertFails(deleteDoc(doc(db, 'invites/newbie@example.com')));
  });
});

describe('news is published by the writer and read by approved accounts', () => {
  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'news/n1'), { title: 'Ny version', body: '' });
    });
  });

  test('an approved account reads it; a pending one does not', async () => {
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'news/n1')));
    await assertFails(getDoc(doc(as(env, 'pat').firestore(), 'news/n1')));
  });

  test('an approved account cannot publish, and a reader-admin cannot either', async () => {
    await assertFails(setDoc(doc(as(env, 'alice').firestore(), 'news/n2'), { title: 'x' }));
    await assertFails(setDoc(doc(as(env, 'reader').firestore(), 'news/n2'), { title: 'x' }));
    await assertSucceeds(setDoc(doc(as(env, 'lars').firestore(), 'news/n2'), { title: 'x' }));
  });
});

describe('a deletion request is a request, not a deletion', () => {
  test('an approved account files one for itself with the server time', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(
      setDoc(doc(db, 'deletionRequests/alice'), { requestedAt: serverTimestamp() }),
    );
  });

  test('a client-chosen timestamp is refused', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(
      setDoc(doc(db, 'deletionRequests/alice'), { requestedAt: new Date(0) }),
    );
  });

  test('nobody files one on somebody else behalf, and a pending account files none', async () => {
    await assertFails(setDoc(
      doc(as(env, 'bob').firestore(), 'deletionRequests/alice'),
      { requestedAt: serverTimestamp() },
    ));
    await assertFails(setDoc(
      doc(as(env, 'pat').firestore(), 'deletionRequests/pat'),
      { requestedAt: serverTimestamp() },
    ));
  });

  test('the request cannot be withdrawn by deleting it, even by the writer', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'deletionRequests/alice'), { requestedAt: new Date(0) });
    });
    await assertFails(deleteDoc(doc(as(env, 'alice').firestore(), 'deletionRequests/alice')));
    await assertFails(deleteDoc(doc(as(env, 'lars').firestore(), 'deletionRequests/alice')));
  });
});
