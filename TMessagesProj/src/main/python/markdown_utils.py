"""
markdown_utils — exteraGram's Markdown helper for plugins.

    from markdown_utils import parse_markdown
    parsed = parse_markdown("*bold* _italic_ `code`")
    params["message"] = parsed.text
    params["entities"] = [e.to_tlrpc_object() for e in parsed.entities]

Supports Telegram MarkdownV2 markers (*bold*, _italic_, __underline__, ~strike~, ||spoiler||,
`code`, ```lang fenced blocks```, [text](url), [emoji](document_id), > quotes, **> expandable
quotes) plus the common **bold** / ~~strike~~ spellings, with backslash escapes. Offsets are in
UTF-16 code units. Unbalanced markers are kept as literal text instead of failing the send.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Tuple

from extera_utils.text_formatting import RawEntity, TLEntityType, _parse_markdown

__all__ = ["parse_markdown", "ParsedMessage", "RawEntity", "TLEntityType"]


@dataclass
class ParsedMessage:
    text: str
    entities: Tuple[RawEntity, ...]

    @property
    def message(self) -> str:
        return self.text

    def to_tlrpc_entities(self):
        return [entity.to_tlrpc_object() for entity in self.entities]


def parse_markdown(text) -> ParsedMessage:
    plain, entities = _parse_markdown("" if text is None else str(text))
    return ParsedMessage(plain, tuple(entities))
