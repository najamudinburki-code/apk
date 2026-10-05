"use strict";

// Hash of this APK's enrollment-only invitation. No account password is bundled.
const BUNDLED_KEY_HASH = "cfbfdaf1b38ad2f652d717ee5f5f1485502c24ed91b43040012ff9126577e82c";
function installationKeyHash() {
  const value = process.env.AUTO_ENROLLMENT_KEY_HASH ?? BUNDLED_KEY_HASH;
  if (value === "disabled") return null;
  if (!/^[a-f0-9]{64}$/.test(value)) throw new Error("Invalid automatic enrollment configuration");
  return value;
}
module.exports = { installationKeyHash };
