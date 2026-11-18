const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_creator_1";
const BOB_UID = "bob_creator_2";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

test("Unauthenticated users cannot read or write any ClipPulse collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("projects").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("clips").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("campaigns").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("publications").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("campaigns").doc("camp_1").set({
      userId: ALICE_UID,
      name: "Illegal Campaign",
      creatorHandle: "@streamer",
      requiredMention: "@streamer",
      requiredHashtags: ["#shorts"],
      minDurationSec: 15,
      maxDurationSec: 60,
      watermarkCorner: "TOP_LEFT",
      cpmRateUsd: 1.5,
      active: true,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Owner can create, list, and partially update projects and clips without @DocumentId id field", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").set({
      userId: ALICE_UID,
      sourceUrl: "https://youtube.com/watch?v=dQw4w9WgXcQ",
      platform: "YOUTUBE",
      ingestMode: "VOD",
      title: "Ranked Finals Stream",
      streamerHandle: "@PulseGamer",
      durationSec: 3600,
      bucketSec: 10,
      beforeSec: 20,
      afterSec: 10,
      gapSec: 90,
      topCount: 5,
      peakMultiplier: 9.1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("clips").doc("clip_1").set({
      userId: ALICE_UID,
      projectId: "proj_1",
      title: "9.1x Clutch Moment",
      streamerHandle: "@PulseGamer",
      startSec: 420,
      endSec: 455,
      spikeMultiplier: 9.1,
      viralScore: 94,
      layoutPreset: "WORD_POP",
      hookBannerText: "9.1x CHAT SPIKE",
      hookBannerStyle: "VOLT_PILL",
      creditWatermark: "Credit: @PulseGamer",
      chatOverlayEnabled: true,
      seoTitle: "He actually pulled this off?! #shorts",
      seoDescription: "Did you see 0:18? Credit: @PulseGamer #shorts #gaming",
      seoTags: ["gaming clips", "viral shorts"],
      pinnedComment: "Rate this clutch 1-10!",
      status: "DETECTED",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("clips").doc("clip_1").update({
      layoutPreset: "SPLIT_SCREEN",
      status: "RENDERED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("clips").get());

  // Reject shadow @DocumentId `id` field and future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("clips").doc("clip_bad").set({
      id: "clip_bad",
      userId: ALICE_UID,
      projectId: "proj_1",
      title: "Bad Clip",
      streamerHandle: "@PulseGamer",
      startSec: 10,
      endSec: 40,
      spikeMultiplier: 5.0,
      viralScore: 80,
      layoutPreset: "WORD_POP",
      hookBannerText: "WAIT FOR IT",
      hookBannerStyle: "VOLT_PILL",
      creditWatermark: "Credit: @PulseGamer",
      chatOverlayEnabled: false,
      seoTitle: "Test #shorts",
      seoDescription: "Test",
      seoTags: ["shorts"],
      pinnedComment: "Comment below",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("clips").doc("clip_1").update({
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Owner can manage campaigns and publications while cross-user access is blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 4000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("campaigns").doc("camp_1").set({
      userId: ALICE_UID,
      name: "Season 4 Clipping Bounty",
      creatorHandle: "@PulseGamer",
      requiredMention: "@PulseGamer",
      requiredHashtags: ["#shorts", "#pulsegamer"],
      minDurationSec: 15,
      maxDurationSec: 60,
      watermarkCorner: "TOP_LEFT",
      cpmRateUsd: 1.5,
      active: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("publications").doc("pub_1").set({
      userId: ALICE_UID,
      clipId: "clip_1",
      campaignId: "camp_1",
      clipTitle: "9.1x Clutch Moment",
      platform: "YOUTUBE_SHORTS",
      privacyStatus: "PUBLIC",
      youtubeVideoId: "abc123xyz",
      youtubeUrl: "https://youtube.com/shorts/abc123xyz",
      viewsCount: 12400,
      cpmRateUsd: 1.5,
      compliancePassed: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Cross-user isolation checks
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("campaigns").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("publications").doc("pub_1").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("publications").doc("pub_1").update({
      viewsCount: 999999,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});
