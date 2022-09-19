// Could not make it work with scalajs.
// In scalajs, this inside eval was always the global this.
// In pure javascript it works.

function argumentsFunction(proxy) {
   return function inner(...args) {
     return proxy(args);
   }
}

function evalBound(data) {
  function inner(s) {
    try {
      return eval(`with (this) { ${s} }`);
    } catch (e) {
      console.error(`Failed to evaluate template code: ${s}`);
      throw e;
    }
  }

  return inner.bind(data);
}

module.exports = {evalBound, argumentsFunction}