"""Deterministic fingerprinting of source structure. Source is parsed, never executed."""
from collections import defaultdict
from dataclasses import dataclass
from functools import lru_cache
from importlib import import_module
from itertools import combinations
from typing import Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, model_validator
from tree_sitter import Language, Parser

from .runtime import authorized

router = APIRouter(dependencies=[Depends(authorized)])
LanguageName = Literal["PYTHON", "JAVA", "CPP", "C", "JAVASCRIPT"]
GRAMMAR = {
    "PYTHON": "tree_sitter_python", "JAVA": "tree_sitter_java", "CPP": "tree_sitter_cpp",
    "C": "tree_sitter_c", "JAVASCRIPT": "tree_sitter_javascript",
}
K = 12
MAX_MATCHES = 8
PUNCTUATION = frozenset(("(", ")", "{", "}", "[", "]", ";", ","))
STRINGS = frozenset(("string", "string_literal", "raw_string_literal", "char_literal",
                     "character_literal", "concatenated_string", "template_string", "regex", "system_lib_string"))


class Submission(BaseModel):
    submission_id: str = Field(min_length=1, max_length=100)
    language: LanguageName
    text: str = Field(min_length=1, max_length=96_000)


class SimilarityRequest(BaseModel):
    submissions: list[Submission] = Field(min_length=2, max_length=100)
    threshold: float = Field(default=0, ge=0, le=1)

    @model_validator(mode="after")
    def unique_submissions(self):
        if len({s.submission_id for s in self.submissions}) != len(self.submissions):
            raise ValueError("submission_id must be unique within a question")
        return self


@dataclass(frozen=True)
class Fingerprint:
    lines: list[int]
    grams: dict[tuple[str, ...], list[int]]


@lru_cache
def grammar(language: str) -> Language:
    return Language(import_module(GRAMMAR[language]).language())


def fingerprint(text: str, language: str) -> Fingerprint:
    root = Parser(grammar(language)).parse(text.encode("utf-8")).root_node
    tokens = []
    lines = []
    stack = [(root, False)]
    while stack:
        node, closing = stack.pop()
        kind = node.type
        if closing:
            tokens.append("/" + kind)
            lines.append(node.end_point.row + int(node.end_point.column > 0))
            continue
        if node.is_missing or "comment" in kind or kind in PUNCTUATION or kind in {"module", "program", "translation_unit"}:
            if kind in {"module", "program", "translation_unit"}:
                stack.extend((child, False) for child in reversed(node.children))
            continue
        if "identifier" in kind or kind in {"this", "super", "self"}:
            token = "ID"
        elif kind in STRINGS:
            token = "STR"
        elif kind in {"integer", "float", "number"} or kind.endswith(("_integer_literal", "_floating_point_literal", "_number_literal")):
            token = "NUM"
        elif kind in {"true", "false", "null", "none", "None", "undefined", "nullptr"}:
            token = "CONST"
        else:
            token = kind
            if node.child_count:
                stack.append((node, True))
                stack.extend((child, False) for child in reversed(node.children))
        tokens.append(token)
        lines.append(node.start_point.row + 1)

    # Exact k-gram keys avoid hash-collision false positives. Each occurrence can match once.
    grams = defaultdict(list)
    for offset in range(len(tokens) - K + 1):
        grams[tuple(tokens[offset:offset + K])].append(offset)
    return Fingerprint(lines, grams)


def segment(left, right, offset, diagonal, count):
    width = count + K - 1
    a = left.lines[offset:offset + width]
    b = right.lines[offset + diagonal:offset + diagonal + width]
    return {"left_lines": {"start": min(a), "end": max(a)},
            "right_lines": {"start": min(b), "end": max(b)},
            "structure_tokens": width}


def compare(left: Fingerprint, right: Fingerprint, threshold: float):
    common = left.grams.keys() & right.grams.keys()
    hits = sum(min(len(left.grams[key]), len(right.grams[key])) for key in common)
    total = max(0, len(left.lines) - K + 1) + max(0, len(right.lines) - K + 1)
    score = 2 * hits / total if total else 0.0
    if score < threshold:
        return None

    diagonals = defaultdict(set)
    for key in common:
        left_positions = left.grams[key]
        right_positions = right.grams[key]
        if len(left_positions) * len(right_positions) <= 32:
            for a in left_positions:
                for b in right_positions:
                    diagonals[b - a].add(a)
        else:
            for a, b in zip(left_positions, right_positions):
                diagonals[b - a].add(a)
    runs = []
    for delta, offsets in diagonals.items():
        sequence = sorted(offsets)
        start = end = sequence[0]
        for offset in sequence[1:]:
            if offset != end + 1:
                runs.append((end - start + 1, start, delta))
                start = offset
            end = offset
        runs.append((end - start + 1, start, delta))

    matches = []
    seen = set()
    for count, start, delta in sorted(runs, key=lambda run: (-run[0], run[1], run[1] + run[2])):
        match = segment(left, right, start, delta, count)
        a, b = match["left_lines"], match["right_lines"]
        key = (a["start"], a["end"], b["start"], b["end"])
        if key not in seen:
            seen.add(key)
            matches.append(match)
        if len(matches) == MAX_MATCHES:
            break
    return {"score": score, "matches": matches,
            "summary": f"{hits} shared {K}-grams across {total} fingerprint occurrences (multiset Dice)."}


@router.post("/similarity")
def similarity(request: SimilarityRequest):
    groups = defaultdict(list)
    for submission in sorted(request.submissions, key=lambda entry: entry.submission_id):
        if submission.text.strip():
            groups[submission.language].append(submission)
    pairs = []
    for language, group in sorted(groups.items()):
        if len(group) < 2:
            continue
        entries = [(s.submission_id, fingerprint(s.text, language)) for s in group]
        for (a, left), (b, right) in combinations(entries, 2):
            result = compare(left, right, request.threshold)
            if result is not None:
                pairs.append({"left_submission_id": a, "right_submission_id": b, "language": language, **result})
    return {"pairs": pairs}
