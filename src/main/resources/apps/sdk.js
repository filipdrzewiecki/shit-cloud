// ShitCloud SDK: inlined as the first script of every app document, so it exists before any app code runs.
// Apps never reach the API: each call is a message to the ShitCloud page framing the app, which checks it,
// makes the call for the signed-in user and answers. Apps that never use window.shitcloud are not affected.
(function () {
  if (window.shitcloud) return;

  var TIMEOUT_MS = 15000;
  var pending = {};
  var nextId = 1;
  // Framed by ShitCloud, the parent is always a ShitCloud page: the app's CSP (frame-ancestors) refuses
  // any other site. Opened in a tab of its own, the app has no parent and no data.
  var framed = window.parent !== window;

  function failure(code, message) {
    var error = new Error(message);
    error.name = 'ShitCloudError';
    error.code = code;
    return error;
  }

  window.addEventListener('message', function (event) {
    // Only the framing page can answer; any other window posting here is ignored.
    if (event.source !== window.parent) return;
    var reply = event.data;
    if (!reply || reply.shitcloud !== 1 || !pending[reply.id]) return;
    var call = pending[reply.id];
    delete pending[reply.id];
    clearTimeout(call.timer);
    if (reply.ok) call.resolve(reply.result);
    else call.reject(failure(reply.error && reply.error.code || 'ERROR', reply.error && reply.error.message || 'Failed'));
  });

  function request(op, args) {
    return new Promise(function (resolve, reject) {
      if (!framed) {
        reject(failure('NOT_IN_SHITCLOUD', 'Data works only when the app is opened in ShitCloud'));
        return;
      }
      var id = nextId++;
      var message = { shitcloud: 1, id: id, op: op };
      for (var key in args) message[key] = args[key];
      pending[id] = {
        resolve: resolve,
        reject: reject,
        timer: setTimeout(function () {
          delete pending[id];
          reject(failure('TIMEOUT', 'ShitCloud did not answer'));
        }, TIMEOUT_MS)
      };
      try {
        // '*': the app cannot know which ShitCloud address frames it; frame-ancestors guarantees it is one.
        window.parent.postMessage(message, '*');
      } catch (e) {
        clearTimeout(pending[id].timer);
        delete pending[id];
        reject(failure('BAD_REQUEST', 'Only plain data (objects, arrays, text, numbers) can be stored'));
      }
    });
  }

  // Documents come back flat: the app's fields plus id, _version, _updatedBy and _updatedAt. Writing such an
  // object back is fine: id and fields starting with _ are not stored.
  function flatten(doc) {
    var flat = {};
    for (var key in doc.data) flat[key] = doc.data[key];
    flat.id = doc.id;
    flat._version = doc.version;
    flat._updatedBy = doc.updatedBy;
    flat._updatedAt = doc.updatedAt;
    return flat;
  }

  var db = Object.freeze({
    // find('days', {userId: me.id, date: {$gte: '2026-10-01'}}, {sort: {date: 1}, limit: 100})
    find: function (collection, filter, options) {
      options = options || {};
      return request('find', { collection: collection, filter: filter, sort: options.sort, limit: options.limit })
        .then(function (docs) { return docs.map(flatten); });
    },
    // set('days', 'filip_2026-10-20', {type: 'off'}) creates or replaces; {expectedVersion} guards against
    // overwriting someone else's change (VERSION_CONFLICT).
    set: function (collection, id, data, options) {
      return request('set', { collection: collection, docId: id, data: data,
        expectedVersion: options && options.expectedVersion }).then(flatten);
    },
    // add('votes', {place: 'Pizza'}) creates a document with a new id and returns that id.
    add: function (collection, data) {
      return request('add', { collection: collection, data: data }).then(function (doc) { return doc.id; });
    },
    // remove('days', 'filip_2026-10-20') resolves to false when there was nothing to remove.
    remove: function (collection, id, options) {
      return request('remove', { collection: collection, docId: id,
        expectedVersion: options && options.expectedVersion });
    }
  });

  Object.defineProperty(window, 'shitcloud', {
    value: Object.freeze({
      version: 2,
      // {id, displayName, guest} of the person using the app.
      me: function () { return request('me', {}); },
      db: db
    }),
    enumerable: true
  });
})();
