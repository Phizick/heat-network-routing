'use strict';

const $ = (id) => document.getElementById(id);
const fmt = (v, d = 0) => v == null ? '-' : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: d, minimumFractionDigits: d });
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

async function api(url, opts) {
  const r = await fetch(url, opts);
  if (!r.ok) {
    let msg = r.status + ' ' + r.statusText;
    try { const j = await r.json(); msg = j.error || j.message || msg; } catch (e) { /* не JSON */ }
    throw new Error(msg);
  }
  return r.json();
}

// ---------------------------------------------------------------- карта

const map = L.map('map', { preferCanvas: true }).setView([55.698, 37.64], 15);
// по умолчанию Leaflet 1.9 подставляет в подпись флаг: оставляем только текст,
// указание источника тайлов убирать нельзя по условиям OSM и Esri
map.attributionControl.setPrefix('<a href="https://leafletjs.com/" target="_blank" rel="noopener">Leaflet</a>');
const baseLayers = {
  'Светлая (Esri)': L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
    maxZoom: 16, maxNativeZoom: 16, attribution: 'Tiles &copy; Esri',
  }),
  'OpenStreetMap': L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }),
};
baseLayers['Светлая (Esri)'].addTo(map);

const RESTRICTION_STYLE = {
  oks: { color: '#868e96', fillColor: '#adb5bd' },
  water: { color: '#1c7ed6', fillColor: '#a5d8ff' },
  railway: { color: '#795548', fillColor: '#d7ccc8' },
  park: { color: '#2f9e44', fillColor: '#b2f2bb' },
  social_area: { color: '#f08c00', fillColor: '#ffe8cc' },
  prohibited_site: { color: '#c92a2a', fillColor: '#ffc9c9' },
  road: { color: '#f59f00', fillColor: '#fff3bf' },
  tram_tracks: { color: '#e67700', fillColor: '#ffe066' },
  gas_pipeline: { color: '#fab005', fillColor: '#fab005' },
  power_cable: { color: '#7048e8', fillColor: '#7048e8' },
};
const DN_COLORS = [
  [80, '#74c0fc'], [125, '#4dabf7'], [150, '#228be6'], [200, '#1971c2'],
  [300, '#5f3dc4'], [500, '#862e9c'], [99999, '#a61e4d'],
];
const dnColor = (dn) => (DN_COLORS.find(([max]) => (dn || 0) <= max) || DN_COLORS[DN_COLORS.length - 1])[1];

const layers = {
  restrictions: L.featureGroup().addTo(map),
  network: L.featureGroup().addTo(map),
  oks: L.featureGroup().addTo(map),
  result: L.featureGroup().addTo(map),
};
L.control.layers(baseLayers, {
  'Ограничения': layers.restrictions,
  'Существующая сеть': layers.network,
  'Точки ОКС': layers.oks,
  'Новая сеть': layers.result,
}, { collapsed: false }).addTo(map);

function popup(props, title) {
  const rows = Object.entries(props)
    .filter(([k, v]) => v !== null && v !== '' && typeof v !== 'object')
    .map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(typeof v === 'number' ? fmt(v, 2) : v)}</td></tr>`)
    .join('');
  return `<div class="popup"><b>${esc(title)}</b><table>${rows}</table></div>`;
}

function drawInput(fc) {
  Object.values(layers).forEach((l) => l.clearLayers());
  L.geoJSON(fc, {
    filter: (f) => f.properties.object_type === 'restriction',
    style: (f) => {
      const s = RESTRICTION_STYLE[f.properties.restriction_type] || { color: '#e03131', fillColor: '#ffc9c9' };
      return { ...s, weight: 1, fillOpacity: 0.45 };
    },
    onEachFeature: (f, l) => l.bindPopup(popup(f.properties, 'Ограничение: ' + f.properties.restriction_type)),
  }).addTo(layers.restrictions);

  L.geoJSON(fc, {
    filter: (f) => ['heat_network', 'heat_chamber', 'source'].includes(f.properties.object_type),
    style: (f) => ({ color: '#c92a2a', weight: 2 + Math.min(4, (f.properties.diameter || 300) / 150), opacity: 0.85 }),
    pointToLayer: (f, ll) => f.properties.object_type === 'source'
      ? L.circleMarker(ll, { radius: 8, color: '#c92a2a', fillColor: '#ffe066', fillOpacity: 1, weight: 3 })
      : L.circleMarker(ll, { radius: 3.5, color: '#c92a2a', fillColor: '#fff', fillOpacity: 1, weight: 1.5 }),
    onEachFeature: (f, l) => l.bindPopup(popup(f.properties, {
      heat_network: 'Существующая сеть', heat_chamber: 'Существующая камера', source: 'Источник',
    }[f.properties.object_type])),
  }).addTo(layers.network);

  L.geoJSON(fc, {
    filter: (f) => f.properties.object_type === 'oks_connection_point',
    pointToLayer: (f, ll) => L.circleMarker(ll, {
      radius: 3 + Math.sqrt(f.properties.flow_tph || 1) * 0.55, color: '#d9480f', fillColor: '#ff922b', fillOpacity: 0.9, weight: 1.5,
    }),
    onEachFeature: (f, l) => {
      l.bindPopup(popup(f.properties, 'Точка подключения ОКС'));
      l.bindTooltip(`${f.properties.id} · ${fmt(f.properties.flow_tph, 1)} т/ч`);
    },
  }).addTo(layers.oks);

  const bounds = L.featureGroup([layers.network, layers.oks]).getBounds();
  if (bounds.isValid()) map.fitBounds(bounds.pad(0.05));
}

let resultFc = null;
let selected = 'v1';
let mode = '2d';
let currentSolution = null;

function drawResult() {
  layers.result.clearLayers();
  if (!resultFc) return;
  const all = $('showAll').checked;
  const visible = (f) => f.properties.variant_id && (all || f.properties.variant_id === selected)
    && f.properties.object_type !== 'variant_summary';
  L.geoJSON(resultFc, {
    filter: visible,
    style: (f) => {
      // style в Leaflet применяется и к circleMarker: точки оформляются в pointToLayer
      if (f.geometry.type !== 'LineString') return {};
      const other = f.properties.variant_id !== selected;
      return {
        color: other ? '#adb5bd' : dnColor(f.properties.diameter),
        weight: other ? 3 : 3 + Math.min(4, f.properties.diameter / 100),
        opacity: other ? 0.7 : 0.95,
        dashArray: f.properties.laying_method === 'special' ? '6 5' : null,
      };
    },
    pointToLayer: (f, ll) => {
      const other = f.properties.variant_id !== selected;
      if (f.properties.object_type === 'technical_node') {
        return L.circleMarker(ll, { radius: 3, color: '#495057', fillColor: '#fff', fillOpacity: 1, weight: 1.5 });
      }
      const tieIn = f.properties.chamber_role === 'tie_in';
      return L.circleMarker(ll, {
        radius: tieIn ? 5.5 : 4,
        color: other ? '#adb5bd' : (tieIn ? '#c92a2a' : '#1971c2'),
        fillColor: '#fff', fillOpacity: 1, weight: 2.5,
      });
    },
    onEachFeature: (f, l) => l.bindPopup(popup(f.properties, {
      heat_network: 'Новый участок', heat_chamber: f.properties.chamber_role === 'tie_in' ? 'Новая камера (врезка)' : 'Новая камера (ветвление)',
      technical_node: 'Технический узел',
    }[f.properties.object_type] || f.properties.object_type)),
  }).addTo(layers.result);
  // выбранный вариант поверх остальных
  layers.result.eachLayer((g) => g.eachLayer && g.eachLayer((l) => {
    if (l.feature && l.feature.properties.variant_id === selected && l.bringToFront) l.bringToFront();
  }));
}

// ---------------------------------------------------------------- легенда

function legend() {
  const parts = [];
  parts.push('<div><span class="sw" style="border-color:#c92a2a"></span>существующая сеть</div>');
  for (let i = 0; i < DN_COLORS.length; i++) {
    const lo = i === 0 ? 50 : DN_COLORS[i - 1][0] + 1;
    const hi = DN_COLORS[i][0];
    parts.push(`<div><span class="sw" style="border-color:${DN_COLORS[i][1]}"></span>новая сеть ДУ ${lo}${hi < 99999 ? '-' + hi : '+'}</div>`);
  }
  parts.push('<div><span class="sw dash" style="border-color:#1971c2"></span>спецпереход (дорога, коммуникация)</div>');
  parts.push('<div><span class="dot" style="border-color:#c92a2a"></span>новая камера-врезка</div>');
  parts.push('<div><span class="dot" style="border-color:#1971c2"></span>камера ветвления</div>');
  parts.push('<div><span class="dot" style="border-color:#d9480f;background:#ff922b"></span>точка ОКС (размер - расход)</div>');
  for (const [k, s] of Object.entries(RESTRICTION_STYLE)) {
    parts.push(`<div><span class="box" style="background:${s.fillColor};border-color:${s.color}"></span>${k}</div>`);
  }
  $('legend').innerHTML = parts.join('');
}

// ---------------------------------------------------------------- датасеты

async function loadDatasets(selectId) {
  const list = await api('/api/datasets');
  const sel = $('datasets');
  sel.innerHTML = '<option value="">- датасеты -</option>' + list.map((d) =>
    `<option value="${d.id}">${esc(d.name || d.id)} · ${d.status} · ${new Date(d.created_at).toLocaleString('ru-RU')}</option>`).join('');
  const target = selectId || (list.find((d) => d.status === 'READY') || {}).id;
  if (target) {
    sel.value = target;
    await selectDataset(target);
  }
}

async function selectDataset(id) {
  $('solve').disabled = true;
  $('datasetInfo').innerHTML = '';
  $('solutions').innerHTML = '<option value="">- расчёты -</option>';
  $('variantsBox').hidden = true;
  resultFc = null;
  if (!id) return;
  const d = await api('/api/datasets/' + id);
  const r = d.report || {};
  if (d.status === 'PROCESSING') {
    $('datasetInfo').innerHTML = '<span class="spinner"></span> разбор файла…';
    setTimeout(() => selectDataset(id), 1500);
    return;
  }
  if (d.status === 'FAILED') {
    $('datasetInfo').innerHTML = `<span class="err">Ошибка загрузки: ${esc(d.error)}</span>`;
    return;
  }
  const counts = Object.entries(r.objectTypeCounts || {}).map(([k, v]) => `${k}: <b>${v}</b>`).join(', ');
  const rtypes = Object.entries(r.restrictionTypeCounts || {}).map(([k, v]) => `${k} ${v}`).join(', ');
  const issues = Object.entries(r.issueCounts || {});
  $('datasetInfo').innerHTML = `Принято <b>${fmt(r.featuresStored)}</b>, отброшено <b>${fmt(r.featuresRejected)}</b><br>${counts}`
    + (rtypes ? `<br>ограничения: ${esc(rtypes)}` : '')
    + `<br>суммарный расход ОКС: <b>${fmt(r.totalFlowTph, 2)} т/ч</b>`
    + (issues.length ? `<br><span class="warn">Замечания: ${issues.map(([k, v]) => esc(k) + ' × ' + v).join(', ')}</span>` : '');
  $('solve').disabled = false;
  drawInput(await api('/api/datasets/' + id + '/geojson'));
  const sols = await api('/api/datasets/' + id + '/solutions');
  $('solutions').innerHTML = '<option value="">- расчёты -</option>' + sols.map((s) =>
    `<option value="${s.id}">${new Date(s.created_at).toLocaleString('ru-RU')} · ${s.status}</option>`).join('');
  const ready = sols.find((s) => s.status === 'READY' || s.status === 'PROCESSING');
  if (ready) {
    $('solutions').value = ready.id;
    await selectSolution(ready.id);
  }
}

// ---------------------------------------------------------------- расчёт

async function selectSolution(id) {
  $('variantsBox').hidden = true;
  $('solveStatus').innerHTML = '';
  resultFc = null;
  drawResult();
  if (!id) return;
  const s = await api('/api/solutions/' + id);
  if (s.status === 'PROCESSING') {
    const t = Math.round((Date.now() - new Date(s.created_at)) / 1000);
    $('solveStatus').innerHTML = `<span class="spinner"></span> идёт расчёт… ${t} с`;
    setTimeout(() => { if ($('solutions').value === id) selectSolution(id); }, 2000);
    return;
  }
  if (s.status === 'FAILED') {
    $('solveStatus').innerHTML = `<span class="err">Ошибка расчёта: ${esc(s.error)}</span>`;
    return;
  }
  currentSolution = s;
  await showMode(id);
}

async function showMode(id) {
  const sum = currentSolution.summary;
  const hasDepth = !!sum.depthVariants;
  document.querySelector('input[name="mode"][value="depth"]').disabled = !hasDepth;
  if (!hasDepth) mode = '2d';
  document.querySelector(`input[name="mode"][value="${mode}"]`).checked = true;
  const variants = mode === 'depth' ? sum.depthVariants : sum.variants;
  $('solveStatus').innerHTML = `Готово за <b>${fmt(sum.elapsedMs / 1000, 1)} с</b> · ОКС: <b>${sum.oksCount}</b>, расход ${fmt(sum.totalFlowTph, 2)} т/ч`
    + (sum.diagnostics && sum.diagnostics.length ? `<br><span class="warn">${sum.diagnostics.map(esc).join('<br>')}</span>` : '');
  resultFc = await api(`/api/solutions/${id}/geojson?mode=${mode}`);
  $('download').href = `/api/solutions/${id}/geojson?download=true&mode=${mode}`;
  $('download').textContent = mode === 'depth' ? 'Скачать GeoJSON (режим с глубиной)' : 'Скачать GeoJSON (формат ТЗ, 2D)';
  const tbody = $('variants').querySelector('tbody');
  tbody.innerHTML = variants.map((v) => {
    const errs = v.validation_errors || [];
    return `<tr data-v="${v.variant_id}"><td>${v.rank}</td><td><b>${fmt(v.score, 3)}</b></td>
      <td>${fmt(v.calculated_cost / 1e6, 1)}</td><td>${fmt(v.new_network_length)}</td><td>${v.new_chambers}</td>
      <td>${v.tie_ins}</td><td class="${errs.length ? 'bad' : 'ok'}">${errs.length ? errs.length + ' ✗' : '✓'}</td></tr>`;
  }).join('');
  const view = { variants };
  tbody.querySelectorAll('tr').forEach((tr) => tr.addEventListener('click', () => selectVariant(tr.dataset.v, view)));
  $('variantsBox').hidden = false;
  selectVariant(variants[0] ? variants[0].variant_id : 'v1', view);
}

document.querySelectorAll('input[name="mode"]').forEach((r) => r.addEventListener('change', () => {
  mode = document.querySelector('input[name="mode"]:checked').value;
  if (currentSolution) showMode(currentSolution.id).catch(showError);
}));

function selectVariant(vid, sum) {
  selected = vid;
  document.querySelectorAll('#variants tbody tr').forEach((tr) => tr.classList.toggle('active', tr.dataset.v === vid));
  const v = sum.variants.find((x) => x.variant_id === vid);
  const summaryFeature = resultFc.features.find((f) => f.properties.object_type === 'variant_summary' && f.properties.variant_id === vid);
  const sp = summaryFeature ? summaryFeature.properties : {};
  const errs = v.validation_errors || [];
  const unconnected = Object.entries(sp.unconnected_reasons || {});
  $('variantDetails').innerHTML = `
    <b>Вариант ${v.rank}</b> (стратегия: ${esc(v.strategy)})<br>
    трубы: <b>${fmt(v.pipe_cost / 1e6, 2)}</b> млн руб. · камеры: <b>${fmt(v.chamber_construction_cost / 1e6, 1)}</b> млн руб.
    · врезки в сущ. камеры: <b>${v.existing_chamber_tie_in_count}</b><br>
    итоговая стоимость C: <b>${fmt(v.calculated_cost / 1e6, 2)}</b> млн руб. · длина L: <b>${fmt(v.new_network_length, 1)}</b> м
    ${unconnected.length ? `<br><span class="err">Не подключены: ${unconnected.map(([k, r]) => esc(k) + ' (' + esc(r) + ')').join('; ')}</span>` : '<br>Подключены все ОКС'}
    ${errs.length ? `<br><span class="err">Нарушения:</span><ul>${errs.map((e) => '<li>' + esc(e) + '</li>').join('')}</ul>` : '<br><span class="ok">Проверка правил пройдена</span>'}
    ${v.notes && v.notes.length ? `<br>Заметки:<ul>${v.notes.map((e) => '<li>' + esc(e) + '</li>').join('')}</ul>` : ''}`;
  drawResult();
}

// ---------------------------------------------------------------- события

$('file').addEventListener('change', () => {
  const f = $('file').files[0];
  $('fileName').textContent = f ? `${f.name} (${fmt(f.size / 1048576, 1)} МБ)` : 'Выбрать GeoJSON…';
  $('upload').disabled = !f;
});

async function uploadFile(f) {
  const body = new FormData();
  body.append('file', f);
  const r = await api('/api/datasets', { method: 'POST', body });
  await loadDatasets(r.id);
}

$('demo').addEventListener('click', async () => {
  $('demo').disabled = true;
  $('datasetInfo').innerHTML = '<span class="spinner"></span> загрузка демо-набора…';
  try {
    const blob = await (await fetch('/demo-dataset.geojson')).blob();
    await uploadFile(new File([blob], 'demo-dataset-with-roads.geojson', { type: 'application/geo+json' }));
  } catch (e) {
    $('datasetInfo').innerHTML = `<span class="err">${esc(e.message)}</span>`;
  } finally {
    $('demo').disabled = false;
  }
});

$('upload').addEventListener('click', async () => {
  const f = $('file').files[0];
  if (!f) return;
  $('upload').disabled = true;
  $('datasetInfo').innerHTML = '<span class="spinner"></span> загрузка файла…';
  try {
    await uploadFile(f);
  } catch (e) {
    $('datasetInfo').innerHTML = `<span class="err">${esc(e.message)}</span>`;
  } finally {
    $('upload').disabled = false;
  }
});

$('datasets').addEventListener('change', () => selectDataset($('datasets').value).catch(showError));
$('solutions').addEventListener('change', () => selectSolution($('solutions').value).catch(showError));
$('showAll').addEventListener('change', drawResult);

$('solve').addEventListener('click', async () => {
  const id = $('datasets').value;
  if (!id) return;
  $('solve').disabled = true;
  try {
    const r = await api(`/api/datasets/${id}/solve`, { method: 'POST' });
    const opt = document.createElement('option');
    opt.value = r.id;
    opt.textContent = new Date().toLocaleString('ru-RU') + ' · PROCESSING';
    $('solutions').insertBefore(opt, $('solutions').options[1] || null);
    $('solutions').value = r.id;
    await selectSolution(r.id);
  } catch (e) {
    showError(e);
  } finally {
    $('solve').disabled = false;
  }
});

function showError(e) {
  $('solveStatus').innerHTML = `<span class="err">${esc(e.message || e)}</span>`;
}

legend();
loadDatasets().catch(showError);
