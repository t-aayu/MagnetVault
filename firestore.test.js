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

// --- BOOKMARKS ---

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

test("Owner can create, list, update, and delete bookmarks; ghost fields & cross-user fail", async () => {
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

  // List & orderBy matching Kotlin repository query
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("bookmarks")
      .orderBy("createdAt", "desc")
      .get()
  );

  // Partial update of mutable fields
  await assertSucceeds(
    bmRef.update({
      seeders: 1350,
      updatedAt: pastDate(1000),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    bmRef.update({
      createdAt: pastDate(500),
      updatedAt: pastDate(500),
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

// --- SEARCH HISTORY ---

test("Owner can manage search_history; future timestamps and cross-user access fail", async () => {
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
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    histRef.update({
      category: "ALL",
      updatedAt: pastDate(1000),
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("search_history")
      .orderBy("updatedAt", "desc")
      .limit(20)
      .get()
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

// --- MIRROR CONFIGS ---

test("Owner can create and update mirror_configs; invalid provider or mismatched doc ID fails", async () => {
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
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    mirrorRef.update({
      enabled: false,
      activeMirrorUrl: "https://1337x.st",
      updatedAt: pastDate(1000),
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
