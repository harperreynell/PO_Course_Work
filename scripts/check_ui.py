#!/usr/bin/env python3
"""Exercise the browser UI on the reference corpus."""
import argparse
from playwright.sync_api import expect, sync_playwright

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:8080")
args = parser.parse_args()
with sync_playwright() as playwright:
    browser = playwright.chromium.launch()
    page = browser.new_page(viewport={"width": 1120, "height": 800})
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(args.url)
    page.locator("#threads").fill("3")
    page.get_by_role("button", name="Побудувати індекс").click()
    expect(page.locator("#documents")).to_have_text("8")
    expect(page.locator("#workers")).to_have_text("3")
    for width, query, match in [(1120, "orbit", "space.txt"), (390, "violin", "music.txt")]:
        page.set_viewport_size({"width": width, "height": 800})
        page.get_by_label("Англійський запит").fill(query)
        page.get_by_role("button", name="Знайти").click()
        expect(page.locator("#stages .complete")).to_have_count(3, timeout=65000)
        expect(page.locator("#results")).to_contain_text(match)
        expect(page.locator("#terms")).not_to_be_empty()
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    browser.close()
    assert not errors, errors
print("PASS · Chromium desktop and mobile widths, index form, results and three WS stages")
