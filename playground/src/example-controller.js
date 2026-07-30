const persistenceKey = "prose-playground-authored";
const persistenceVersion = 1;

export function createExampleController({ examples, onActivate, storage }) {
  const examplesById = new Map(examples.map((example) => [example.id, example]));
  const defaultExample = examples[0];
  let state;

  function canonicalState(example) {
    return {
      selectedExample: example.id,
      source: example.source,
      title: example.title,
    };
  }

  function persist() {
    try {
      storage?.setItem(persistenceKey, JSON.stringify({
        selectedExample: state.selectedExample,
        source: state.source,
        version: persistenceVersion,
      }));
    } catch {
      // Persistence is optional; storage may be unavailable or full.
    }
  }

  function restoredState() {
    try {
      const record = JSON.parse(storage?.getItem(persistenceKey));
      const example = examplesById.get(record?.selectedExample);
      if (record?.version === persistenceVersion && typeof record.source === "string" && example) {
        return { ...canonicalState(example), source: record.source };
      }
    } catch {
      // Invalid or unavailable storage falls back to the default Example.
    }
    return canonicalState(defaultExample);
  }

  function activate(nextState) {
    state = nextState;
    persist();
    onActivate(state);
    return state;
  }

  return {
    edit(source) {
      state = { ...state, source };
      persist();
    },
    getState: () => state,
    reset() {
      return activate(canonicalState(examplesById.get(state.selectedExample)));
    },
    select(id) {
      return activate(canonicalState(examplesById.get(id)));
    },
    start() {
      state = restoredState();
      onActivate(state);
      return state;
    },
  };
}
