#!/usr/bin/env python3
"""Repair LOTRO-invalid ABC [CE]3/2 chord lengths by moving lengths onto notes.

This is an optional recovery tool for already exported ABC files. It does not
alter headers, instruments, octave mappings, or notes outside these chords.
Use on a COPY; Maestro's built-in exporter has been fixed separately.
"""
import argparse
from fractions import Fraction
from pathlib import Path
import re

# Deliberately strict: reject unfamiliar chord syntax instead of corrupting it.
CHORD = re.compile(r"\[([^\]\r\n]+)\]((?:\d+(?:/\d*)?|/\d*|/+))")
NOTE = re.compile(r"([_^=]*[A-Ga-g][',]*)(\d+(?:/\d*)?|/\d*|/+)?(-?)")


def length(value: str | None) -> Fraction:
    if not value:
        return Fraction(1)
    if value.startswith('/') and value == '/' * len(value):
        return Fraction(1, 2 ** len(value))
    if value.startswith('/'):
        return Fraction(1, int(value[1:]))
    if value.endswith('/'):
        return Fraction(int(value[:-1]), 2)
    return Fraction(value)


def repair(source: str) -> tuple[str, int]:
    changed = 0

    def convert(match: re.Match[str]) -> str:
        nonlocal changed
        contents, multiplier_text = match.groups()
        notes = list(NOTE.finditer(contents))
        if not notes or ''.join(n.group() for n in notes) != contents:
            raise ValueError(f'Unsupported chord notation: [{contents}]{multiplier_text}')
        multiplier = length(multiplier_text)
        if multiplier <= 0:
            raise ValueError(f'Invalid chord duration: {multiplier_text}')
        result = []
        for n in notes:
            pitch, old_duration, tie = n.groups()
            duration = length(old_duration) * multiplier
            formatted = str(duration.numerator)
            if duration.denominator != 1:
                formatted += f'/{duration.denominator}'
            result.append(pitch + formatted + tie)
        changed += 1
        return '[' + ''.join(result) + ']'

    return CHORD.sub(convert, source), changed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error('Output must be a different file; this tool will not overwrite its input')
    original = args.input.read_text(encoding='utf-8')
    fixed, count = repair(original)
    if count == 0:
        print('No LOTRO-invalid chord duration suffixes found')
    args.output.write_text(fixed, encoding='utf-8')
    print(f'Repaired {count} chord durations: {args.output}')


if __name__ == '__main__':
    main()
