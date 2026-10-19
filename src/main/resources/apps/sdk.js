// ShitCloud SDK: inlined as the first script of every app document, so it exists before any app code runs.
// For now it only announces itself; the data bridge (db, me) will be added to this object.
(function () {
  if (window.shitcloud) return;
  Object.defineProperty(window, 'shitcloud', {
    value: Object.freeze({ version: 1 }),
    enumerable: true
  });
})();
