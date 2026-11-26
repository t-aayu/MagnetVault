const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- V1 LEGACY COMPATIBILITY + SECURITY TESTS ---

test("Unauthenticated user cannot read or write bookmarks", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("bookmarks").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("bookmarks")
      .doc("bm_1")
      .set({
        userId: ALICE_UID,
        title: "Ubuntu 24.04 LTS ISO",
        source: "X1337",
        category: "APPS",
        sizeLabel: "5.8 GB",
        sizeBytes: 6227702579,
        seeders: 450,
        leechers: 20,
        uploadDateLabel: "2026-10-01",
        magnetUri: "magnet:?xt=urn:btih:abcdef1234567890",
        infoHash: "abcdef1234567890",
        detailPageUrl: "https://1337x.to/torrent/12345/ubuntu/",
        qualityTags: [],
        createdAt: pastDate(),
        updatedAt: pastDate(),
      })
  );
});

test("V1 legacy bookmark create, update, and delete succeed while ghost fields & cross-user fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(10000);

  const bmRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("bookmarks")
    .doc("bm_1");

  await assertSucceeds(
    bmRef.set({
      userId: ALICE_UID,
      title: "Big Buck Bunny 2160p HEVC",
      source: "YTS",
      category: "MOVIES",
      sizeLabel: "2.4 GB",
      sizeBytes: 2576980377,
      seeders: 1200,
      leechers: 85,
      uploadDateLabel: "2026-09-12",
      magnetUri: "magnet:?xt=urn:btih:0123456789abcdef",
      infoHash: "0123456789abcdef",
      detailPageUrl: "https://yts.mx/movies/big-buck-bunny",
      qualityTags: ["4K", "HEVC"],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update of mutable fields (v1 style)
  await assertSucceeds(
    bmRef.update({
      seeders: 1350,
      updatedAt: pastDate(1000),
    })
  );

  // V2 client upgrading a v1 doc sets revision = 1 and tombstone/active metadata
  await assertSucceeds(
    bmRef.update({
      seeders: 1400,
      deleted: false,
      revision: 1,
      lastOpId: "op_upgrade_v1_to_v2",
      canonicalIdentity: "btih:0123456789abcdef",
      legacyDocId: "bm_1",
      updatedAt: pastDate(500),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    bmRef.update({
      createdAt: pastDate(200),
      updatedAt: pastDate(200),
    })
  );

  // Reject ghost field / @DocumentId 'id' on create
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("bookmarks")
      .doc("bm_ghost")
      .set({
        id: "bm_ghost",
        userId: ALICE_UID,
        title: "Ghost ID Test",
        source: "EZTV",
        category: "TV",
        sizeLabel: "1.1 GB",
        sizeBytes: 1100000000,
        seeders: 10,
        leechers: 2,
        uploadDateLabel: "Today",
        magnetUri: "magnet:?xt=urn:btih:aaaa",
        infoHash: "aaaa",
        detailPageUrl: "https://eztvx.to/ep/1",
        qualityTags: ["1080p"],
        createdAt: created,
        updatedAt: created,
      })
  );

  // Reject cross-user read & delete
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("bookmarks").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("bookmarks")
      .doc("bm_1")
      .delete()
  );

  // Owner delete succeeds
  await assertSucceeds(bmRef.delete());
});

// --- V2 TOMBSTONES, CAS REVISIONS & RESURRECTION ---

test("V2 bookmark supports monotonic CAS revisions, soft-delete tombstones, and resurrection, while rejecting skipped/regressed revisions or future deletedAt", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(15000);
  const bmRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("bookmarks")
    .doc("bm_v2_cas");

  // 1. Create v2 bookmark with revision = 1
  await assertSucceeds(
    bmRef.set({
      userId: ALICE_UID,
      title: "Debian 12.6 Netinst",
      source: "TPB",
      category: "APPS",
      sizeLabel: "620 MB",
      sizeBytes: 650117120,
      seeders: 300,
      leechers: 12,
      uploadDateLabel: "2026-10-02",
      magnetUri: "magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98",
      infoHash: "fedcba9876543210fedcba9876543210fedcba98",
      detailPageUrl: "https://thepiratebay.org/description.php?id=1",
      qualityTags: [],
      revision: 1,
      deleted: false,
      lastOpId: "op_1",
      canonicalIdentity: "btih:fedcba9876543210fedcba9876543210fedcba98",
      legacyDocId: "bm_v2_cas",
      createdAt: created,
      updatedAt: created,
    })
  );

  // 2. Reject skipped revision (1 -> 3) or regressed revision (1 -> 0 / 1 -> 1)
  await assertFails(
    bmRef.update({
      revision: 3,
      lastOpId: "op_skip",
      updatedAt: pastDate(5000),
    })
  );
  await assertFails(
    bmRef.update({
      revision: 1,
      lastOpId: "op_same",
      updatedAt: pastDate(5000),
    })
  );

  // 3. Reject tombstone missing deletedAt or with future deletedAt
  await assertFails(
    bmRef.update({
      deleted: true,
      revision: 2,
      lastOpId: "op_no_ts",
      updatedAt: pastDate(4000),
    })
  );
  await assertFails(
    bmRef.update({
      deleted: true,
      deletedAt: new Date(Date.now() + 120000),
      revision: 2,
      lastOpId: "op_future_ts",
      updatedAt: pastDate(4000),
    })
  );

  // 4. Valid tombstone soft-delete (revision 1 -> 2)
  await assertSucceeds(
    bmRef.update({
      deleted: true,
      deletedAt: pastDate(4000),
      revision: 2,
      lastOpId: "op_delete_2",
      updatedAt: pastDate(4000),
    })
  );

  // 5. Explicit re-save resurrects tombstone (revision 2 -> 3, deleted: false)
  await assertSucceeds(
    bmRef.update({
      deleted: false,
      revision: 3,
      lastOpId: "op_resurrect_3",
      updatedAt: pastDate(1000),
    })
  );
});

// --- SEARCH HISTORY V1 & V2 ---

test("Owner can manage v1 & v2 search_history tombstones; future timestamps and cross-user access fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(8000);

  const histRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("search_history")
    .doc("q_ubuntu");

  await assertSucceeds(
    histRef.set({
      userId: ALICE_UID,
      query: "ubuntu 24.04",
      category: "APPS",
      revision: 1,
      deleted: false,
      lastOpId: "op_h1",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Soft-delete search history entry with revision 1 -> 2
  await assertSucceeds(
    histRef.update({
      deleted: true,
      deletedAt: pastDate(2000),
      revision: 2,
      lastOpId: "op_h2",
      updatedAt: pastDate(2000),
    })
  );

  // Resurrect search history on replay (revision 2 -> 3)
  await assertSucceeds(
    histRef.update({
      category: "ALL",
      deleted: false,
      revision: 3,
      lastOpId: "op_h3",
      updatedAt: pastDate(500),
    })
  );

  // Reject revision regression (3 -> 2)
  await assertFails(
    histRef.update({
      revision: 2,
      lastOpId: "op_h_bad",
      updatedAt: pastDate(300),
    })
  );

  // Reject future timestamp tampering
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("search_history")
      .doc("q_future")
      .set({
        userId: ALICE_UID,
        query: "future test",
        category: "ALL",
        createdAt: new Date(Date.now() + 120000),
        updatedAt: new Date(Date.now() + 120000),
      })
  );

  // Cross-user rejected
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("search_history").get()
  );

  await assertSucceeds(histRef.delete());
});

// --- MIRROR CONFIGS V1 & V2 ---

test("Owner can create and update v1 & v2 mirror_configs; invalid provider, skipped revision, or mismatched doc ID fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(9000);

  const mirrorRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("mirror_configs")
    .doc("X1337");

  await assertSucceeds(
    mirrorRef.set({
      userId: ALICE_UID,
      provider: "X1337",
      enabled: true,
      mirrors: ["https://1337x.to", "https://x1337x.cc", "https://1337x.st"],
      activeMirrorUrl: "https://x1337x.cc",
      revision: 1,
      lastOpId: "op_m1",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    mirrorRef.update({
      enabled: false,
      activeMirrorUrl: "https://1337x.st",
      revision: 2,
      lastOpId: "op_m2",
      updatedAt: pastDate(1000),
    })
  );

  // Skipped revision rejected (2 -> 4)
  await assertFails(
    mirrorRef.update({
      enabled: true,
      revision: 4,
      lastOpId: "op_m_bad",
      updatedAt: pastDate(500),
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("mirror_configs")
      .get()
  );

  // Reject mismatched doc ID vs provider
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("mirror_configs")
      .doc("WRONG_ID")
      .set({
        userId: ALICE_UID,
        provider: "EZTV",
        enabled: true,
        mirrors: ["https://eztvx.to"],
        activeMirrorUrl: "https://eztvx.to",
        createdAt: created,
        updatedAt: created,
      })
  );

  // Reject cross-user update
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("mirror_configs")
      .doc("X1337")
      .update({ enabled: true, updatedAt: pastDate(500) })
  );
});
