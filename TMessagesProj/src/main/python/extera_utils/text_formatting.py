"""Basic text formatting helpers for exteraGram-compatible plugins."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from html.parser import HTMLParser
import re


class TLEntityType(Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    url: str | None = None
    language: str | None = None
    document_id: int | None = None
    collapsed: bool = False

    def to_tl_entity(self):
        from org.telegram.tgnet import TLRPC

        factories = {
            TLEntityType.CODE: TLRPC.TL_messageEntityCode,
            TLEntityType.PRE: TLRPC.TL_messageEntityPre,
            TLEntityType.STRIKETHROUGH: TLRPC.TL_messageEntityStrike,
            TLEntityType.TEXT_LINK: TLRPC.TL_messageEntityTextUrl,
            TLEntityType.BOLD: TLRPC.TL_messageEntityBold,
            TLEntityType.ITALIC: TLRPC.TL_messageEntityItalic,
            TLEntityType.UNDERLINE: TLRPC.TL_messageEntityUnderline,
            TLEntityType.SPOILER: TLRPC.TL_messageEntitySpoiler,
            TLEntityType.CUSTOM_EMOJI: TLRPC.TL_messageEntityCustomEmoji,
            TLEntityType.BLOCKQUOTE: TLRPC.TL_messageEntityBlockquote,
        }
        entity = factories[self.type]()
        entity.offset = int(self.offset)
        entity.length = int(self.length)
        if self.type is TLEntityType.TEXT_LINK:
            entity.url = self.url or ""
        elif self.type is TLEntityType.PRE:
            entity.language = self.language or ""
        elif self.type is TLEntityType.CUSTOM_EMOJI:
            entity.document_id = int(self.document_id or 0)
        elif self.type is TLEntityType.BLOCKQUOTE:
            entity.collapsed = bool(self.collapsed)
        return entity

    # Names used by exteraGram's markdown_utils and older plugin samples.
    to_tlrpc_object = to_tl_entity
    to_tlrpc = to_tl_entity


def _utf16_len(value: str) -> int:
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def _entity_length(start: int, end: int) -> int:
    return max(0, end - start)


class _HtmlEntityParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.entities: list[RawEntity] = []
        self.stack: list[tuple[str, TLEntityType, int, dict[str, object]]] = []

    @property
    def current_offset(self) -> int:
        return _utf16_len("".join(self.parts))

    def handle_data(self, data: str) -> None:
        self.parts.append(data)

    def handle_entityref(self, name: str) -> None:
        self.parts.append(f"&{name};")

    def handle_charref(self, name: str) -> None:
        self.parts.append(f"&#{name};")

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_dict = {key.lower(): value for key, value in attrs}
        tag = tag.lower()
        if tag == "br":
            self.parts.append("\n")
            return

        mapping = {
            "b": TLEntityType.BOLD,
            "strong": TLEntityType.BOLD,
            "i": TLEntityType.ITALIC,
            "em": TLEntityType.ITALIC,
            "u": TLEntityType.UNDERLINE,
            "s": TLEntityType.STRIKETHROUGH,
            "del": TLEntityType.STRIKETHROUGH,
            "strike": TLEntityType.STRIKETHROUGH,
            "code": TLEntityType.CODE,
            "pre": TLEntityType.PRE,
            "spoiler": TLEntityType.SPOILER,
            "tg-spoiler": TLEntityType.SPOILER,
            "blockquote": TLEntityType.BLOCKQUOTE,
            "a": TLEntityType.TEXT_LINK,
            "emoji": TLEntityType.CUSTOM_EMOJI,
        }
        entity_type = mapping.get(tag)
        if entity_type is None:
            return

        meta: dict[str, object] = {}
        if entity_type is TLEntityType.TEXT_LINK:
            href = attrs_dict.get("href")
            if not href:
                return
            meta["url"] = href
        elif entity_type is TLEntityType.PRE:
            language = attrs_dict.get("language") or ""
            class_name = attrs_dict.get("class") or ""
            if not language and isinstance(class_name, str):
                match = re.search(r"(?:^|\s)language-([A-Za-z0-9_+-]+)", class_name)
                if match:
                    language = match.group(1)
            meta["language"] = language
        elif entity_type is TLEntityType.CUSTOM_EMOJI:
            document_id = attrs_dict.get("id") or attrs_dict.get("document_id")
            if not document_id:
                return
            try:
                meta["document_id"] = int(str(document_id).strip())
            except ValueError:
                return
        elif entity_type is TLEntityType.BLOCKQUOTE:
            meta["collapsed"] = "collapsed" in attrs_dict or "expandable" in attrs_dict

        self.stack.append((tag, entity_type, self.current_offset, meta))

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        for index in range(len(self.stack) - 1, -1, -1):
            start_tag, entity_type, start, meta = self.stack[index]
            if start_tag != tag:
                continue
            del self.stack[index]
            end = self.current_offset
            length = _entity_length(start, end)
            if length <= 0:
                return
            self.entities.append(RawEntity(
                entity_type,
                start,
                length,
                url=meta.get("url"),
                language=meta.get("language"),
                document_id=meta.get("document_id"),
                collapsed=bool(meta.get("collapsed", False)),
            ))
            return


def _add_entity(entities: list[RawEntity], entity_type: TLEntityType, start: int, end: int, **kwargs) -> None:
    length = _entity_length(start, end)
    if length > 0:
        entities.append(RawEntity(entity_type, start, length, **kwargs))


_MD_ESCAPABLE = set("\\`*_~|[]()>#+-=.!{}")


class _Builder:
    """Accumulates plain text while tracking the UTF-16 offset in O(1) per append."""

    def __init__(self):
        self.parts: list[str] = []
        self.offset = 0
        self.entities: list[RawEntity] = []

    def add(self, value: str) -> None:
        if value:
            self.parts.append(value)
            self.offset += _utf16_len(value)

    def entity(self, entity_type: TLEntityType, start: int, **kwargs) -> None:
        _add_entity(self.entities, entity_type, start, self.offset, **kwargs)

    def merge(self, text: str, entities: list[RawEntity]) -> None:
        base = self.offset
        self.add(text)
        for entity in entities:
            entity.offset += base
            self.entities.append(entity)

    def text(self) -> str:
        return "".join(self.parts)


def _is_word(ch: str) -> bool:
    return ch.isalnum()


def _find_unescaped(text: str, needle: str, start: int) -> int:
    """Index of the next unescaped ``needle`` outside inline code spans, or -1."""
    i = start
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if text.startswith(needle, i):
            return i
        if ch == "`" and needle != "`":
            fence = "```" if text.startswith("```", i) else "`"
            close = text.find(fence, i + len(fence))
            if close < 0:
                i += 1
                continue
            i = close + len(fence)
            continue
        i += 1
    return -1


def _closing_marker(text: str, marker: str, start: int) -> int:
    """Find the closing delimiter for an emphasis marker, honouring word boundaries."""
    i = start
    while True:
        close = _find_unescaped(text, marker, i)
        if close < 0:
            return -1
        if close == start:
            return -1  # empty span: "**", "____" stay literal
        if text[close - 1].isspace():
            i = close + 1
            continue
        after = text[close + len(marker)] if close + len(marker) < len(text) else ""
        if marker in ("_", "__") and after and _is_word(after):
            i = close + 1
            continue  # intraword: snake_case_name
        if len(marker) == 1 and after == marker:
            # "*a**" / "_a__": the doubled run belongs to a longer marker; keep looking.
            i = close + 2
            continue
        return close


_MARKERS = (
    ("**", TLEntityType.BOLD),
    ("__", TLEntityType.UNDERLINE),
    ("||", TLEntityType.SPOILER),
    ("~~", TLEntityType.STRIKETHROUGH),
    ("*", TLEntityType.BOLD),
    ("_", TLEntityType.ITALIC),
    ("~", TLEntityType.STRIKETHROUGH),
)


def _match_link(text: str, index: int):
    """Parse ``[label](url)`` at ``index``; returns (label, url, end) or None."""
    if text[index] != "[":
        return None
    i = index + 1
    depth = 1
    while i < len(text):
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
            if depth == 0:
                break
        elif ch == "\n" and text.startswith("\n\n", i):
            return None
        i += 1
    else:
        return None
    label_end = i
    if label_end + 1 >= len(text) or text[label_end + 1] != "(":
        return None
    j = label_end + 2
    depth = 1
    url_chars = []
    while j < len(text):
        ch = text[j]
        if ch == "\\" and j + 1 < len(text):
            url_chars.append(text[j + 1])
            j += 2
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        elif ch in "\n":
            return None
        url_chars.append(ch)
        j += 1
    else:
        return None
    return text[index + 1:label_end], "".join(url_chars).strip(), j + 1


_EMOJI_URL = re.compile(r"^(?:tg://emoji\?id=)?(\d+)$")


def _parse_fence(text: str, index: int):
    """``` fenced block at ``index``; returns (code, language, end) or None."""
    close = text.find("```", index + 3)
    if close < 0:
        return None
    block = text[index + 3:close]
    language = ""
    if "\n" in block:
        first, rest = block.split("\n", 1)
        if not first.strip() or re.fullmatch(r"[A-Za-z0-9_+#.-]+", first.strip()):
            language = first.strip()
            block = rest
    if block.endswith("\n"):
        block = block[:-1]
    return block, language, close + 3


def _parse_inline_markdown(text: str) -> tuple[str, list[RawEntity]]:
    out = _Builder()
    index = 0
    n = len(text)
    while index < n:
        ch = text[index]

        if ch == "\\" and index + 1 < n and text[index + 1] in _MD_ESCAPABLE:
            out.add(text[index + 1])
            index += 2
            continue

        if text.startswith("```", index):
            fence = _parse_fence(text, index)
            if fence is not None:
                code, language, index = fence
                start = out.offset
                out.add(code)
                out.entity(TLEntityType.PRE, start, language=language)
                continue

        if ch == "`":
            close = text.find("`", index + 1)
            if close > index + 1:
                start = out.offset
                out.add(text[index + 1:close])
                out.entity(TLEntityType.CODE, start)
                index = close + 1
                continue

        if ch == "!" and index + 1 < n and text[index + 1] == "[":
            link = _match_link(text, index + 1)
            if link is not None:
                label, url, end = link
                emoji = _EMOJI_URL.match(url)
                if emoji:
                    start = out.offset
                    out.add(label)
                    out.entity(TLEntityType.CUSTOM_EMOJI, start, document_id=int(emoji.group(1)))
                    index = end
                    continue

        if ch == "[":
            link = _match_link(text, index)
            if link is not None:
                label, url, end = link
                emoji = _EMOJI_URL.match(url)
                start = out.offset
                if emoji:
                    out.add(label)
                    out.entity(TLEntityType.CUSTOM_EMOJI, start, document_id=int(emoji.group(1)))
                else:
                    out.merge(*_parse_inline_markdown(label))
                    out.entity(TLEntityType.TEXT_LINK, start, url=url)
                index = end
                continue

        if text.startswith("***", index) and index + 3 < n and not text[index + 3].isspace():
            close = _find_unescaped(text, "***", index + 3)
            if close > index + 3 and not text[close - 1].isspace():
                start = out.offset
                out.merge(*_parse_inline_markdown(text[index + 3:close]))
                out.entity(TLEntityType.BOLD, start)
                out.entity(TLEntityType.ITALIC, start)
                index = close + 3
                continue

        matched = False
        for marker, entity_type in _MARKERS:
            if not text.startswith(marker, index):
                continue
            body_start = index + len(marker)
            if body_start >= n or text[body_start].isspace():
                continue
            if marker in ("_", "__") and index > 0 and _is_word(text[index - 1]):
                continue  # intraword underscore
            close = _closing_marker(text, marker, body_start)
            if close < 0:
                continue
            start = out.offset
            out.merge(*_parse_inline_markdown(text[body_start:close]))
            out.entity(entity_type, start)
            index = close + len(marker)
            matched = True
            break
        if matched:
            continue

        out.add(ch)
        index += 1

    return out.text(), out.entities


def _split_markdown_blocks(text: str):
    """Yield (kind, payload, collapsed) segments: plain text or merged '>' quote blocks.

    Lines inside ``` fences are never treated as quotes.
    """
    lines = text.splitlines(True)
    in_fence = False
    plain: list[str] = []
    quote: list[str] = []
    collapsed = False

    def flush_quote():
        nonlocal quote, collapsed
        if quote:
            body = "".join(quote)
            trailing = ""
            if body.endswith("\n"):
                body, trailing = body[:-1], "\n"
            if collapsed and body.endswith("||"):
                body = body[:-2]
            yield ("quote", body, collapsed)
            if trailing:
                yield ("plain", trailing, False)
        quote = []
        collapsed = False

    for line in lines:
        if not in_fence:
            stripped = line
            is_quote = False
            if line.startswith("**>"):
                is_quote, stripped = True, line[3:]
                if not quote:
                    collapsed = True
            elif line.startswith(">"):
                is_quote, stripped = True, line[1:]
            if is_quote:
                if plain:
                    yield ("plain", "".join(plain), False)
                    plain = []
                if stripped.startswith(" "):
                    stripped = stripped[1:]
                quote.append(stripped)
                continue
            if quote:
                yield from flush_quote()
        if line.count("```") % 2 == 1:
            in_fence = not in_fence
        plain.append(line)
    if quote:
        yield from flush_quote()
    if plain:
        yield ("plain", "".join(plain), False)


def _sort_entities(entities: list[RawEntity]) -> list[RawEntity]:
    return sorted(entities, key=lambda e: (e.offset, -e.length))


def _parse_markdown(text: str) -> tuple[str, list[RawEntity]]:
    out = _Builder()
    for kind, payload, collapsed in _split_markdown_blocks(text):
        start = out.offset
        out.merge(*_parse_inline_markdown(payload))
        if kind == "quote":
            out.entity(TLEntityType.BLOCKQUOTE, start, collapsed=collapsed)
    return out.text(), _sort_entities(out.entities)


def _parse_html(text: str) -> tuple[str, list[RawEntity]]:
    parser = _HtmlEntityParser()
    parser.feed(text)
    parser.close()
    for tag, _entity_type, _start, _meta in reversed(list(parser.stack)):
        parser.handle_endtag(tag)  # an unclosed tag spans to the end of the text
    return "".join(parser.parts), _sort_entities(parser.entities)


def parse_text(text: str, parse_mode: str | None = "HTML", is_caption: bool = False) -> dict[str, object]:
    if text is None:
        text = ""
    text = str(text)
    key = "caption" if is_caption else "message"

    if parse_mode is None:
        return {key: text, "entities": []}

    mode = str(parse_mode).strip().lower()
    if mode in ("html", "htm"):
        parsed, raw_entities = _parse_html(text)
    elif mode in ("markdown", "md"):
        parsed, raw_entities = _parse_markdown(text)
    else:
        raise ValueError("unsupported parse_mode: %s" % parse_mode)

    return {
        key: parsed,
        "entities": [entity.to_tl_entity() for entity in raw_entities],
    }
