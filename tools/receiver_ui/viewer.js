"use strict";
let previous = "";
const element = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};
function observation(event) {
  const body = event.body;
  const article = element("div", "event");
  const heading = element("div", "event-head");
  heading.append(element("span", "", new Date(event.received_at).toLocaleTimeString()),
    element("span", "", `${event.wire_bytes} HTTP body bytes · unverified sender`));
  article.append(heading);
  if (body.mode === "paired") {
    heading.lastChild.textContent = `${event.wire_bytes} wire bytes · paired sender verified`;
    article.append(element("p", "label", "Verified & decrypted on this Mac:"),
      element("div", "message", event.decrypted_text),
      element("p", "label", "The network carried ciphertext, a wrapped key, and a signature. This text is local recipient output."));
  } else if (body.mode === "plaintext") {
    article.append(element("p", "label", "The laptop can read this:"),
      element("div", "message", body.text));
  } else {
    article.append(element("p", "label", "Ciphertext + authentication tag · Base64"),
      element("div", "message", body.ciphertext),
      element("p", "label", `Nonce: ${body.nonce} · counter ${body.counter}`),
      element("p", "label", "No decryption key received. The tag has not been verified here."));
  }
  const details = element("details");
  details.append(element("summary", "", "Inspect the exact envelope fields"),
    element("pre", "", JSON.stringify(body, null, 2)));
  article.append(details);
  return article;
}
function render(data) {
  document.getElementById("plain-count").textContent = data.counts.plaintext;
  document.getElementById("encrypted-count").textContent = data.counts.encrypted;
  document.getElementById("duplicate-count").textContent = data.counts.duplicates;
  document.getElementById("paired-count").textContent = data.counts.paired || 0;
  for (const [mode, id] of [["plaintext", "plain-events"], ["encrypted", "encrypted-events"], ["paired", "paired-events"]]) {
    const target = document.getElementById(id);
    const events = data.events.filter(event => event.body.mode === mode);
    target.replaceChildren(...(events.length ? events.map(observation) : [element("div", "empty",
      mode === "paired" ? "Waiting for your paired Android message…" : mode === "plaintext" ? "Waiting for a readable demo message…" : "Waiting for an encrypted envelope…")]));
  }
}
async function poll() {
  const status = document.getElementById("connection");
  try {
    const response = await fetch("/api/events", {cache: "no-store"});
    if (!response.ok) throw new Error("Receiver unavailable");
    const data = await response.json();
    const signature = JSON.stringify(data);
    if (signature !== previous) { render(data); previous = signature; }
    status.textContent = "● Receiver listening";
  } catch (_) { status.textContent = "Receiver disconnected · restart the Python command"; }
  setTimeout(poll, 1200);
}
poll();

let pairingExpiry = 0;
async function pairingStatus() {
  try {
    const response = await fetch("/api/pairing/status", {cache: "no-store"});
    if (!response.ok) throw new Error();
    const status = await response.json();
    document.getElementById("mac-fingerprint").textContent = status.fingerprint;
    document.getElementById("pair-status").textContent = status.paired ? `Paired Android: ${status.sender_id}` : "Ready to pair one Android sender.";
    const addresses = document.getElementById("local-addresses");
    addresses.replaceChildren(...[...status.addresses, "10.0.2.2", "127.0.0.1"].map(address => {
      const option = element("option"); option.value = address; return option;
    }));
    const host = document.getElementById("pair-host");
    if (!host.value) host.value = status.addresses[0] || "";
  } catch (_) {
    document.getElementById("pair-status").textContent = "Paired recipient unavailable. Restart the updated Python receiver; macOS 14+ and Apple Command Line Tools are required.";
  }
}
document.getElementById("new-pairing").addEventListener("click", async () => {
  const button = document.getElementById("new-pairing");
  const target = document.getElementById("pairing-code");
  button.disabled = true;
  try {
    const response = await fetch("/api/pairing/new", {method: "POST", headers: {"Content-Type": "application/json"},
      body: JSON.stringify({host: document.getElementById("pair-host").value.trim(), port: Number(location.port || 80)})});
    if (!response.ok) throw new Error();
    const result = await response.json();
    const image = element("img"); image.alt = "One-time trusted Mac pairing QR";
    image.src = `data:image/png;base64,${result.qr_png}`;
    const expiry = element("p", "label", "Expires in 5 minutes. Keep this QR private."); expiry.id = "qr-expiry";
    const details = element("details");
    const manual = element("textarea"); manual.readOnly = true; manual.value = result.bundle;
    manual.setAttribute("aria-label", "Trusted manual pairing bundle");
    details.append(element("summary", "", "Manual transfer fallback · contains one-time secret"), manual);
    target.replaceChildren(image, expiry, details);
    pairingExpiry = result.expires_at;
  } catch (_) {
    target.replaceChildren(element("p", "empty", "Could not create pairing. Check the private IPv4 address and Mac Keychain, then retry."));
  } finally { button.disabled = false; }
});
setInterval(() => {
  if (!pairingExpiry) return;
  const remaining = pairingExpiry - Math.floor(Date.now() / 1000);
  if (remaining <= 0) {
    document.getElementById("pairing-code").replaceChildren(element("p", "empty", "Pairing QR expired. Create a new one."));
    pairingExpiry = 0;
  } else {
    const label = document.getElementById("qr-expiry");
    if (label) label.textContent = `Expires in ${remaining}s. Keep this QR private.`;
  }
}, 1000);
pairingStatus();
setInterval(pairingStatus, 5000);
