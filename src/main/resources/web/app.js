"use strict";
const byId = id => document.getElementById(id);

async function api(path, body) {
  const options = body ? {method: "POST", headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body)} : {};
  const response = await fetch("/api/" + path, options);
  const data = await response.json();
  if (!response.ok) throw Error(data.error || "Помилка сервера");
  return data;
}

async function refreshStats() {
  const stats = await api("stats");
  byId("documents").textContent = stats.documents;
  byId("build-time").textContent = stats.index_build_time_ms + " мс";
  byId("workers").textContent = stats.threads_used;
  if (!stats.documents) byId("build-status").textContent = "Побудуйте індекс для першого пошуку.";
}

async function health() {
  try {
    const state = await api("health");
    byId("health").textContent = state.ollama ? "Ollama готова" : "Модель недоступна · пошук за словами";
  } catch {
    byId("health").textContent = "Сервер недоступний";
  }
}

byId("build-form").onsubmit = async event => {
  event.preventDefault();
  byId("build").disabled = true;
  byId("build-status").textContent = "Триває індексація…";
  try {
    await api("index", {threads: Number(byId("threads").value)});
    byId("build-status").textContent = "Побудову завершено.";
    await refreshStats();
  } catch (error) {
    byId("build-status").textContent = error.message;
  } finally {
    byId("build").disabled = false;
  }
};

function renderResults(data) {
  const list = byId("results");
  list.replaceChildren();
  for (const hit of data.results) {
    const row = document.createElement("li");
    const title = document.createElement("strong");
    title.textContent = hit.doc_id;
    const score = document.createElement("small");
    score.textContent = "BM25 " + hit.score.toFixed(3);
    const snippet = document.createElement("p");
    snippet.textContent = hit.snippet;
    row.append(title, score, snippet);
    list.append(row);
  }
  byId("notice").textContent = data.results.length
    ? "Показано " + data.results.length + " · " + data.time_ms + " мс"
    : "Збігів не знайдено.";
}

byId("query-form").onsubmit = event => {
  event.preventDefault();
  if (byId("submit").disabled) return;
  const query = byId("query").value.trim();
  if (!query) return;
  const socket = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws/search");
  let finished = false;
  byId("submit").disabled = true;
  byId("results").replaceChildren();
  byId("terms").textContent = "";
  byId("notice").textContent = "Шукаємо…";
  for (const node of byId("stages").children) node.className = "";
  byId("expansion").className = "active";
  socket.onopen = () => socket.send(JSON.stringify({query}));
  socket.onmessage = event => {
    const {stage, data} = JSON.parse(event.data);
    if (stage === "query_expansion") {
      byId("expansion").className = "complete";
      byId("keyword").className = "active";
      byId("terms").textContent = data.expanded_terms.length
        ? "Додано слова · " + data.expanded_terms.join(", ") : "Використано початкові слова.";
    } else if (stage === "keyword_search") {
      byId("keyword").className = "complete";
      renderResults(data);
    } else if (stage === "done") {
      byId("done").className = "complete";
      finished = true;
      socket.close();
    }
  };
  socket.onclose = () => {
    byId("submit").disabled = false;
    if (!finished) byId("notice").textContent = "З’єднання перервано. Повторіть пошук.";
  };
};

refreshStats().catch(error => { byId("build-status").textContent = error.message; });
health();
setInterval(health, 15000);
