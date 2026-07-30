import assert from "node:assert/strict";
import test from "node:test";
import { history, redo, redoDepth, undo, undoDepth } from "@codemirror/commands";
import { EditorState } from "@codemirror/state";
import {
  atInputTransaction,
  atShorthand,
  clearPendingTransaction,
  shorthandPending,
} from "../src/lozenge-shorthand.js";

function harness(doc = "", selection = doc.length, shorthand = true) {
  let state = EditorState.create({
    doc,
    selection: typeof selection === "number" ? { anchor: selection } : selection,
    extensions: shorthand ? [history(), atShorthand] : [history()],
  });
  const target = {
    get state() {
      return state;
    },
    dispatch(transaction) {
      state = transaction.state;
    },
  };
  return {
    get state() {
      return state;
    },
    apply(spec) {
      state = state.update(spec).state;
    },
    directAt(intent = {}) {
      const spec = atInputTransaction(state, {
        data: "@",
        inputType: "insertText",
        isComposing: false,
        compositionStarted: false,
        ...intent,
      });
      if (spec) state = state.update(spec).state;
      return Boolean(spec);
    },
    blur() {
      const spec = clearPendingTransaction(state);
      if (spec) state = state.update(spec).state;
    },
    redo() {
      return redo(target);
    },
    undo() {
      return undo(target);
    },
  };
}

function snapshot(editor) {
  const selection = editor.state.selection.main;
  return {
    doc: editor.state.doc.toString(),
    pending: shorthandPending(editor.state),
    selection: { anchor: selection.anchor, head: selection.head },
  };
}

test("folds uninterrupted direct at-sign runs in pairs without a timeout", () => {
  const editor = harness();
  const expected = ["◊", "@", "@◊", "@@", "@@◊"];

  for (const document of expected) {
    assert.equal(editor.directAt(), true);
    assert.equal(editor.state.doc.toString(), document);
  }
  assert.deepEqual(shorthandPending(editor.state), { from: 2, to: 3 });
});

test("replaces a selection and only folds its pending shorthand lozenge", () => {
  const editor = harness("hello world", { anchor: 6, head: 11 });

  editor.directAt();
  assert.deepEqual(snapshot(editor), {
    doc: "hello ◊",
    pending: { from: 6, to: 7 },
    selection: { anchor: 7, head: 7 },
  });
  editor.directAt();
  assert.deepEqual(snapshot(editor), {
    doc: "hello @",
    pending: null,
    selection: { anchor: 7, head: 7 },
  });

  const adjacent = harness("◊◊", 2);
  adjacent.directAt();
  adjacent.directAt();
  assert.equal(adjacent.state.doc.toString(), "◊◊@");
});

test("every intervening transaction class clears pending shorthand", () => {
  const interruptions = [
    ["movement", { selection: { anchor: 0 }, userEvent: "select.pointer" }],
    ["other edit", { changes: { from: 0, insert: "x" }, userEvent: "input.type" }],
    ["deletion", { changes: { from: 0, to: 1 }, userEvent: "delete.backward" }],
    ["paste", { changes: { from: 1, insert: "@" }, userEvent: "input.paste" }],
    ["drop", { changes: { from: 1, insert: "@" }, userEvent: "input.drop" }],
    ["autocomplete", { changes: { from: 1, insert: "@" }, userEvent: "input.complete" }],
    ["programmatic replacement", { changes: { from: 0, to: 1, insert: "@" } }],
  ];

  for (const [name, transaction] of interruptions) {
    const editor = harness();
    editor.directAt();
    editor.apply(transaction);
    assert.equal(shorthandPending(editor.state), null, name);
    const insertion = editor.state.selection.main.from;
    editor.directAt();
    assert.equal(editor.state.sliceDoc(insertion, insertion + 1), "◊", name);
  }

  const blurred = harness();
  blurred.directAt();
  blurred.blur();
  assert.equal(shorthandPending(blurred.state), null);
  blurred.directAt();
  assert.equal(blurred.state.doc.toString(), "◊◊");
});

test("paste and composition-labelled input stay literal and never become pending", () => {
  for (const intent of [
    { inputType: "insertFromPaste" },
    { inputType: "insertFromDrop" },
    { inputType: "insertReplacementText" },
    { inputType: "insertCompositionText", isComposing: true },
    { isComposing: true },
    { compositionStarted: true },
  ]) {
    const editor = harness();
    assert.equal(editor.directAt(intent), false);
    editor.apply({ changes: { from: 0, insert: "@" }, userEvent: "input.type" });
    assert.deepEqual(snapshot(editor), {
      doc: "@",
      pending: null,
      selection: { anchor: 0, head: 0 },
    });
  }
});

test("uses normal typing history while undo and redo clear pending", () => {
  const editor = harness();
  editor.directAt();
  editor.directAt();
  editor.directAt();

  assert.equal(undoDepth(editor.state), 1);
  assert.equal(editor.undo(), true);
  assert.deepEqual(snapshot(editor), {
    doc: "",
    pending: null,
    selection: { anchor: 0, head: 0 },
  });
  assert.equal(redoDepth(editor.state), 1);
  assert.equal(editor.redo(), true);
  assert.deepEqual(snapshot(editor), {
    doc: "@◊",
    pending: null,
    selection: { anchor: 2, head: 2 },
  });
});

test("a literal Companion editor has standard at-sign input", () => {
  const companion = harness("", 0, false);
  companion.apply({
    changes: { from: 0, insert: "@@" },
    selection: { anchor: 2 },
    userEvent: "input.type",
  });

  assert.equal(companion.state.doc.toString(), "@@");
  assert.equal(shorthandPending(companion.state), null);
});
