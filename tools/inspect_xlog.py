"""Summarize a local unencrypted Mars xlog without printing request contents."""

from collections import Counter
from pathlib import Path
import hashlib
import re
import struct
import sys
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import zlib


MAGICS = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}
KEYWORDS = (
    "AdsPoint", "PageAdBonus", "KuiklyMultiCmd", "KRProtocol",
    "onReward", "rewardad", "5401", "5403", "5404", "积分",
    "PageAdBonus.apk", "kuikly/dynamic/PageAdBonus",
)


def sanitize(excerpt: str) -> str:
    excerpt = re.sub(r'"([^"\n]{1,50})"\s*:\s*"[^"\n]*"',
                     r'"\1":"<value>"', excerpt)
    excerpt = re.sub(r'(?i)(token|cookie|open_?id|guid|qimei|auth|sid|secid|sign|activityID|resourceID|adPosId|posId)\s*[=:]\s*[^,\s)]+',
                     r'\1=<value>', excerpt)
    excerpt = re.sub(r'(?i)(token|cookie|open_?id|guid|qimei|auth|sid|secid|sign)\s*:\s*"[^"]*"',
                     r'\1:"<value>"', excerpt)
    excerpt = re.sub(r'https?://[^ ]+', '<url>', excerpt)
    excerpt = re.sub(r'[0-9A-Fa-f]{16,}', '<id>', excerpt)
    return re.sub(r'\d{4,}', '<n>', excerpt)


def decode(path: Path, time_filter: str = "", terms: tuple[str, ...] = (),
           fetch_output: Path | None = None):
    data = path.read_bytes()
    offset = 0
    magic_counts = Counter()
    keyword_counts = Counter()
    decoded_bytes = 0
    line_count = 0
    first_line_shape = None
    focus_snippets = []
    time_snippets = []
    time_commands = []
    page_bundle = None
    errors = 0
    decompress_errors = Counter()
    while offset + 14 < len(data):
        magic = data[offset]
        if magic not in MAGICS:
            offset += 1
            errors += 1
            continue
        key_length = 4 if magic in {3, 4, 5} else 64
        header_length = 9 + key_length
        if offset + header_length >= len(data):
            break
        length = struct.unpack_from("<I", data, offset + 5)[0]
        end = offset + header_length + length
        if length > len(data) or end >= len(data) or data[end] != 0:
            offset += 1
            errors += 1
            continue
        chunk = data[offset + header_length:end]
        magic_counts[magic] += 1
        if magic in {4, 9}:
            try:
                chunk = zlib.decompressobj(-zlib.MAX_WBITS).decompress(chunk)
            except zlib.error as error:
                errors += 1
                decompress_errors[str(error)] += 1
                offset = end + 1
                continue
        elif magic not in {3, 8}:
            offset = end + 1
            continue
        text = chunk.decode("utf-8", "replace")
        decoded_bytes += len(chunk)
        for line in text.splitlines():
            line_count += 1
            if "KuiklyPageInfo(pageName=PageAdBonusTab3" in line:
                md5_match = re.search(r'fileMd5=([0-9A-Fa-f]{32})', line)
                size_match = re.search(r'fileSize=(\d+)', line)
                url_match = re.search(r'downloadUrl=(https?://[^,\s)]+)', line)
                if md5_match and size_match and url_match:
                    page_bundle = (md5_match.group(1).lower(),
                                   int(size_match.group(1)), url_match.group(1))
            if first_line_shape is None:
                first_line_shape = "".join(
                    "A" if ch.isalpha() else "0" if ch.isdigit() else ch
                    for ch in line[:100]
                )
            lowered = line.lower()
            if time_filter and time_filter in line:
                commands = re.findall(r'\bcmdId\s*[=:]\s*(\d+)', line)
                sequences = re.findall(r'\bseqId\s*[=:]\s*(\d+)', line)
                if commands or sequences:
                    tag = re.findall(r'\[market\]\[([^\]]+)\]', line)
                    clock = re.findall(r'\b\d{2}:\d{2}:\d{2}\.\d{3}\b', line)
                    time_commands.append((clock[0] if clock else "?",
                                          tag[0] if tag else "?", commands, sequences))
            if time_filter and time_filter in line and len(time_snippets) < 80:
                words = terms or (
                    "PointsChain", "PageAdBonusViewModel", "RewardAdManager",
                    "AdsPoint", "KRProtocol", "KuiklyMultiCmd", "ADListener")
                if any(word.lower() in lowered for word in words):
                    limit = 900 if "AdsPointTaskCallbackRequestEngine" in line else 320
                    time_snippets.append(sanitize(line[:limit]))
            for focus in ("5401", "onreward"):
                position = lowered.find(focus)
                if position >= 0 and len(focus_snippets) < 12:
                    excerpt = line[max(0, position - 100):position + 160]
                    focus_snippets.append((focus, sanitize(excerpt)))
            for keyword in KEYWORDS:
                if keyword.lower() in lowered:
                    keyword_counts[keyword] += 1
        offset = end + 1
    print("records", sum(magic_counts.values()), "magic", dict(magic_counts))
    print("scan_errors", errors)
    print("decompress_errors", dict(decompress_errors))
    print("decoded_bytes", decoded_bytes, "lines", line_count,
          "first_line_shape", first_line_shape)
    print("keyword_counts", dict(keyword_counts))
    for focus, snippet in focus_snippets:
        print("focus", focus, snippet)
    for snippet in time_snippets:
        print("time", snippet)
    for clock, tag, commands, sequences in time_commands[:80]:
        print("command", clock, tag, "cmdIds", commands, "seqIds", sequences)
    if page_bundle:
        expected_md5, expected_size, url = page_bundle
        parsed = urlsplit(url)
        print("PageAdBonusTab3 bundle metadata", parsed.hostname,
              "bytes", expected_size, "md5_recorded", bool(expected_md5))
        if fetch_output:
            if parsed.scheme != "https" or not parsed.hostname or not (
                    parsed.hostname.endswith(".qq.com")
                    or parsed.hostname.endswith(".myqcloud.com")
                    or parsed.hostname.endswith(".gtimg.com")):
                raise ValueError("Bundle URL is not a trusted Tencent HTTPS host")
            if expected_size <= 0 or expected_size > 80_000_000:
                raise ValueError("Bundle size is outside expected range")
            request = Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urlopen(request, timeout=30) as response:
                data = response.read(80_000_001)
            if len(data) > 80_000_000:
                raise ValueError("Bundle exceeds download cap")
            digest = hashlib.md5(data).hexdigest()
            print("bundle_downloaded_bytes", len(data),
                  "size_matches", len(data) == expected_size,
                  "md5_matches", digest == expected_md5)
            if digest != expected_md5:
                raise ValueError("Bundle MD5 did not match app metadata")
            fetch_output.write_bytes(data)


if __name__ == "__main__":
    decode(Path(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else "",
           tuple(sys.argv[3].split(",")) if len(sys.argv) > 3 and sys.argv[3] else (),
           Path(sys.argv[4]) if len(sys.argv) > 4 else None)
