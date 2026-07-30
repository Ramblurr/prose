import { StateEffect, StateField } from "@codemirror/state";
import { EditorView } from "@codemirror/view";

const setPendingPair = StateEffect.define();
const pendingPair = StateField.define({
  create() {
    return null;
  },
  update(_value, transaction) {
    for (const effect of transaction.effects) {
      if (effect.is(setPendingPair)) return effect.value;
    }
    return null;
  },
});

export function shorthandPending(state) {
  return state.field(pendingPair, false) ?? null;
}

function pendingPairIsCollapsible(state, pending) {
  const selection = state.selection.main;
  return pending
    && selection.empty
    && selection.head === pending.to
    && state.sliceDoc(pending.from, pending.to) === "◊";
}

export function atInputTransaction(state, intent) {
  if (intent.inputType !== "insertText"
      || intent.data !== "@"
      || intent.isComposing
      || intent.compositionStarted) return null;

  const selection = state.selection.main;
  const pending = shorthandPending(state);
  if (pendingPairIsCollapsible(state, pending)) {
    return {
      changes: { from: pending.from, to: pending.to, insert: "@" },
      selection: { anchor: pending.from + 1 },
      effects: setPendingPair.of(null),
      userEvent: "input.type",
      scrollIntoView: true,
    };
  }

  return {
    changes: { from: selection.from, to: selection.to, insert: "◊" },
    selection: { anchor: selection.from + 1 },
    effects: setPendingPair.of({ from: selection.from, to: selection.from + 1 }),
    userEvent: "input.type",
    scrollIntoView: true,
  };
}

export function clearPendingTransaction(state) {
  return shorthandPending(state) ? { effects: setPendingPair.of(null) } : null;
}

export const atShorthand = [
  pendingPair,
  EditorView.domEventHandlers({
    beforeinput(event, view) {
      const transaction = atInputTransaction(view.state, {
        data: event.data,
        inputType: event.inputType,
        isComposing: event.isComposing,
        compositionStarted: view.compositionStarted,
      });
      if (!transaction) return false;
      view.dispatch(transaction);
      return true;
    },
    blur(_event, view) {
      const transaction = clearPendingTransaction(view.state);
      if (transaction) view.dispatch(transaction);
      return false;
    },
  }),
];
