#!/usr/bin/env python3
"""Black-box acceptance against the eight .txt files in data/sample."""
import argparse
import json
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from websockets.sync.client import connect

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    "ORBIT?!": ["space.txt", "flight.txt"],
    "search index": ["database.txt", "threads.txt"],
    "current": ["ocean.txt"],
    "violin": ["music.txt"],
    "xyzzyplugh": [],
}


def api(base, path, body=None):
    raw = json.dumps(body).encode() if body is not None else None
    req = Request(base + "/api/" + path, data=raw, headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=60) as reply:
        assert reply.status == 200 and reply.headers.get_content_type() == "application/json"
        return json.load(reply)


def millis(value):
    assert type(value) is int and value >= 0, value


def results(value):
    assert set(value) == {"results", "time_ms"} and isinstance(value["results"], list), value
    millis(value["time_ms"])
    previous = float("inf")
    for hit in value["results"]:
        assert set(hit) == {"doc_id", "score", "snippet"}, hit
        assert isinstance(hit["doc_id"], str) and isinstance(hit["snippet"], str) and hit["snippet"]
        assert type(hit["score"]) in (int, float) and 0 < hit["score"] <= previous
        previous = hit["score"]
    return [hit["doc_id"] for hit in value["results"]]


def check(base, require_model):
    state = api(base, "health")
    assert set(state) == {"status", "ollama"} and state["status"] == "ok" and type(state["ollama"]) is bool
    built = api(base, "index", {"threads": 4})
    assert set(built) == {"status", "documents_indexed", "time_ms"}
    assert built["status"] == "ok" and type(built["documents_indexed"]) is int and built["documents_indexed"] == 8
    millis(built["time_ms"])
    assert api(base, "stats") == dict(documents=8, index_build_time_ms=built["time_ms"], threads_used=4)
    evidence = []
    for query, expected in CASES.items():
        reply = api(base, "search", dict(query=query, mode="keyword"))
        actual = results(reply)
        assert actual == expected, (query, expected, actual)
        evidence.append(dict(query=query, expected=expected, actual=actual, response=reply))
    with ThreadPoolExecutor(max_workers=12) as clients:
        def operation(i):
            if i % 7 == 0:
                assert api(base, "index", dict(threads=1 + i % 5))["documents_indexed"] == 8
            else:
                assert results(api(base, "search", dict(query="orbit", mode="keyword"))) == CASES["ORBIT?!"]
        list(clients.map(operation, range(72)))
    for path, body in [("index", {"threads": 0}), ("index", {"threads": 2.5}),
                       ("search", {"query": "x", "mode": "other"}), ("search", {"query": "", "mode": "keyword"})]:
        try:
            api(base, path, body)
        except HTTPError as error:
            assert error.code == 400 and "error" in json.load(error)
        else:
            raise AssertionError((path, body))
    cli = [str(ROOT / "search"), "--url", base]
    normal = subprocess.check_output([*cli, "orbit"], text=True)
    assert "space.txt" in normal and "flight.txt" in normal and "Збігів: 2" in normal
    assert results(json.loads(subprocess.check_output([*cli, "--json", "orbit"]))) == CASES["ORBIT?!"]
    assert subprocess.run([*cli, ""], capture_output=True).returncode != 0
    if require_model:
        until = time.monotonic() + 300
        while not state["ollama"] and time.monotonic() < until:
            time.sleep(2)
            state = api(base, "health")
        assert state["ollama"], "model readiness timeout"
    with connect(base.replace("http", "ws", 1) + "/ws/search", proxy=None) as socket:
        sessions = []
        for query in ("car", "orbit"):
            socket.send(json.dumps({"query": query}))
            frames = [json.loads(socket.recv(timeout=65)) for _ in range(3)]
            expansion, keyword, done = frames
            assert set(expansion) == set(keyword) == {"stage", "data"}
            assert expansion["stage"] == "query_expansion" and keyword["stage"] == "keyword_search"
            assert done == {"stage": "done"} and set(expansion["data"]) == {"original", "expanded_terms"}
            terms = expansion["data"]["expanded_terms"]
            assert expansion["data"]["original"] == query
            assert isinstance(terms, list) and len(set(terms)) == len(terms) <= 6
            assert all(term.isascii() and term.isalpha() and term.islower() and term != query for term in terms)
            if require_model and query == "car":
                assert terms, "live Ollama produced no additional terms"
            results(keyword["data"])
            reference = api(base, "search", dict(query=" ".join([query, *terms]), mode="keyword"))
            assert keyword["data"]["results"] == reference["results"]
            sessions.append(frames)
    return dict(health=state, build=built, search_cases=evidence, websocket=sessions,
                simultaneous_operations=72, cli_text=normal)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--require-model", action="store_true")
    parser.add_argument("--save", type=Path)
    args = parser.parse_args()
    checked = check(args.url.rstrip("/"), args.require_model)
    if args.save:
        args.save.parent.mkdir(parents=True, exist_ok=True)
        args.save.write_text(json.dumps(checked, ensure_ascii=False, indent=2) + "\n")
    print("PASS · REST, ranking, rebuilds, concurrent clients, validation, CLI and WebSocket")
    print("Ollama expansion:", checked["websocket"][0][0]["data"]["expanded_terms"])
