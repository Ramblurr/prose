import { HighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { clojure } from "@codemirror/legacy-modes/mode/clojure";
import { Tag, tags } from "@lezer/highlight";

export const proseTags = {
  control: Tag.define("proseControl", tags.processingInstruction),
  command: Tag.define("proseCommand", tags.tagName),
  symbol: Tag.define("proseSymbol", tags.variableName),
  delimiter: Tag.define("proseDelimiter", tags.bracket),
  verbatim: Tag.define("proseVerbatim", tags.string),
};

const balancedHighlightStyle = HighlightStyle.define([
  { tag: proseTags.control, class: "tok-prose-control" },
  { tag: proseTags.command, class: "tok-prose-command" },
  { tag: proseTags.symbol, class: "tok-prose-symbol" },
  { tag: proseTags.delimiter, class: "tok-prose-delimiter" },
  { tag: proseTags.verbatim, class: "tok-prose-verbatim" },
  { tag: tags.standard(tags.variableName), class: "tok-clj-operator" },
  {
    tag: [tags.keyword, tags.controlKeyword, tags.definitionKeyword, tags.moduleKeyword],
    class: "tok-clj-keyword",
  },
  { tag: [tags.atom, tags.bool, tags.null], class: "tok-clj-atom" },
  { tag: tags.variableName, class: "tok-clj-symbol" },
  { tag: [tags.string, tags.character], class: "tok-clj-string" },
  { tag: tags.number, class: "tok-clj-number" },
  { tag: tags.comment, class: "tok-clj-comment" },
  { tag: tags.bracket, class: "tok-clj-bracket" },
  { tag: tags.meta, class: "tok-clj-meta" },
  { tag: tags.invalid, class: "tok-invalid" },
]);

export const balancedSyntax = syntaxHighlighting(balancedHighlightStyle);

const whitespace = new Set([
  "\t", "\n", "\v", "\f", "\r", " ", "\u00a0", "\u1680",
  "\u2000", "\u2001", "\u2002", "\u2003", "\u2004", "\u2005",
  "\u2006", "\u2007", "\u2008", "\u2009", "\u200a", "\u2028",
  "\u2029", "\u202f", "\u205f", "\u3000",
]);
const symbolDelimiters = new Set(["(", ")", "[", "]", "{", "}", "\""]);
const clojureBaseTokenizer = clojure.startState(2).tokenize;

function whitespaceCharacter(character) {
  return whitespace.has(character);
}

function regularSymbolCharacter(character) {
  return Boolean(character)
    && !whitespaceCharacter(character)
    && character !== "◊"
    && character !== "/"
    && character !== "\\"
    && !symbolDelimiters.has(character);
}

function firstSymbolCharacter(character) {
  return regularSymbolCharacter(character)
    && character !== "#"
    && !/[0-9]/.test(character);
}

function simpleSymbolEnd(text, start) {
  if (!firstSymbolCharacter(text[start])) return null;
  let index = start + 1;
  while (regularSymbolCharacter(text[index])) index += 1;
  return index;
}

function symbolLength(text) {
  const firstEnd = simpleSymbolEnd(text, 0);
  if (firstEnd === null) return 0;
  if (text[firstEnd] !== "/") return firstEnd;
  return simpleSymbolEnd(text, firstEnd + 1) ?? firstEnd;
}

function cloneContext(context) {
  return context
    ? { ...context, prev: cloneContext(context.prev) }
    : context;
}

function cloneClojureState(state) {
  return { ...state, ctx: cloneContext(state.ctx) };
}

function clojureFrame(closing, indentUnit, finishCommand = false) {
  return {
    kind: "clojure",
    closing,
    finishCommand,
    state: clojure.startState(indentUnit),
  };
}

function textFrame(closing = null) {
  return { kind: "text", closing, depth: closing ? 1 : 0 };
}

function commandFrame() {
  return { kind: "command", stage: "after-introducer" };
}

function copyFrame(frame) {
  return frame.kind === "clojure"
    ? { ...frame, state: cloneClojureState(frame.state) }
    : { ...frame };
}

function closeChildFrame(state, frame) {
  state.frames.pop();
  if (frame.finishCommand) state.frames.pop();
}

function consumeText(stream, state, frame) {
  const character = stream.peek();

  if (character === "◊") {
    stream.next();
    state.frames.push(commandFrame());
    return "prose-control";
  }

  if (frame.closing && character === "{") {
    stream.next();
    frame.depth += 1;
    return "prose-delimiter";
  }

  if (frame.closing && character === "}") {
    stream.next();
    frame.depth -= 1;
    if (frame.depth === 0) state.frames.pop();
    return "prose-delimiter";
  }

  while (!stream.eol()) {
    const next = stream.peek();
    if (next === "◊" || (frame.closing && (next === "{" || next === "}"))) break;
    stream.next();
  }
  return null;
}

function consumeClojure(stream, state, frame) {
  const atCodeBoundary = frame.state.tokenize === clojureBaseTokenizer;
  const character = stream.peek();

  if (atCodeBoundary && character === "◊") {
    stream.next();
    state.frames.push(commandFrame());
    return "prose-control";
  }

  if (atCodeBoundary
      && character === frame.closing
      && frame.state.ctx.prev === null) {
    stream.next();
    closeChildFrame(state, frame);
    return "prose-delimiter";
  }

  const proseBoundary = atCodeBoundary && character !== ";"
    ? stream.string.indexOf("◊", stream.pos)
    : -1;
  if (proseBoundary <= stream.pos) return clojure.token(stream, frame.state);

  const line = stream.string;
  try {
    stream.string = line.slice(0, proseBoundary);
    return clojure.token(stream, frame.state);
  } finally {
    stream.string = line;
  }
}

function consumeVerbatim(stream, state) {
  let escaped = false;
  while (!stream.eol()) {
    const character = stream.next();
    if (character === "\"" && !escaped) {
      state.frames.pop();
      break;
    }
    escaped = !escaped && character === "\\";
  }
  return "prose-verbatim";
}

function consumeWhitespace(stream) {
  while (!stream.eol() && whitespaceCharacter(stream.peek())) stream.next();
}

function consumeCommand(stream, state, frame) {
  if (frame.stage === "verbatim") return consumeVerbatim(stream, state);

  if (frame.stage === "after-introducer") {
    const character = stream.peek();
    if (character === "◊") {
      stream.next();
      frame.stage = "name";
      return "prose-control";
    }
    if (character === "|") {
      stream.next();
      frame.stage = "symbol";
      return "prose-control";
    }
    if (character === "\"") {
      stream.next();
      frame.stage = "verbatim";
      return "prose-verbatim";
    }
    if (character === "(") {
      stream.next();
      frame.stage = "child-completes-command";
      state.frames.push(clojureFrame(")", state.indentUnit, true));
      return "prose-delimiter";
    }
    frame.stage = "name";
    return null;
  }

  if (frame.stage === "name" || frame.stage === "symbol") {
    const length = symbolLength(stream.string.slice(stream.pos));
    if (length === 0) {
      if (!stream.eol()) stream.next();
      state.frames.pop();
      return "invalid";
    }
    stream.pos += length;
    const style = frame.stage === "name" ? "prose-command" : "prose-symbol";
    if (frame.stage === "name") frame.stage = "after-argument";
    else state.frames.pop();
    return style;
  }

  if (frame.stage === "after-argument") {
    if (whitespaceCharacter(stream.peek())) {
      consumeWhitespace(stream);
      return null;
    }

    const character = stream.peek();
    if (character === "[") {
      stream.next();
      state.frames.push(clojureFrame("]", state.indentUnit));
      return "prose-delimiter";
    }
    if (character === "{") {
      stream.next();
      state.frames.push(textFrame("}"));
      return "prose-delimiter";
    }

    state.frames.pop();
    return null;
  }

  state.frames.pop();
  return null;
}

const proseStreamParser = {
  name: "prose",
  startState(indentUnit) {
    return { indentUnit, frames: [textFrame()] };
  },
  copyState(state) {
    return { ...state, frames: state.frames.map(copyFrame) };
  },
  token(stream, state) {
    for (let guard = 0; guard < 12; guard += 1) {
      const start = stream.pos;
      const frame = state.frames[state.frames.length - 1] ?? textFrame();
      if (state.frames.length === 0) state.frames.push(frame);

      const style = frame.kind === "text"
        ? consumeText(stream, state, frame)
        : frame.kind === "clojure"
          ? consumeClojure(stream, state, frame)
          : consumeCommand(stream, state, frame);

      if (stream.pos > start || style) return style;
    }

    stream.next();
    return "invalid";
  },
  tokenTable: {
    "prose-control": proseTags.control,
    "prose-command": proseTags.command,
    "prose-symbol": proseTags.symbol,
    "prose-delimiter": proseTags.delimiter,
    "prose-verbatim": proseTags.verbatim,
  },
  mergeTokens: false,
};

export const proseLanguage = StreamLanguage.define(proseStreamParser);
export const clojureLanguage = StreamLanguage.define(clojure);
