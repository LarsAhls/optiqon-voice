// FS-S468: the one definition of unread, shared by the server and mirrored by the app.
//
// A case is unread for its owner while `publicRev` on the case is ahead of `seenPublicRev` in
// users/{ownerUid}/caseReads/{caseId}. `publicRev` moves by exactly one with a writer's public
// reply or public status change (rules: publicBumped(); server: support.mjs), and with nothing
// else — so the owner's own messages, internal notes, retention and deletion never make a case
// unread, and a duplicate of the same event cannot make it unread again. The marker lives under
// the owner's own uid, so another account on the same device never sees or moves it.

export function isUnread(kase, marker) {
  return (kase?.publicRev ?? 0) > (marker?.seenPublicRev ?? 0);
}
