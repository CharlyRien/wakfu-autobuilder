/* ============================================================
   Wakfu Autobuilder — App root: state, search simulation, tweaks
   ============================================================ */
(function () {
  const { useState, useRef, useEffect, useCallback } = React;
  const W = window.WAKFU;
  const C = window.WBComp;

  // ---- tweak defaults ----
  const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
    "accent": "copper",
    "displayFont": "industrial",
    "density": 4,
    "layout": "ring",
    "fill": "energetic",
    "depth": "standard"
  }/*EDITMODE-END*/;

  // ---- initial targets (clone from data, tag as from-data) ----
  const initTargets = () => W.TARGETS.map(t => ({ ...t, _fromData: true }));

  // order in which slots fill during search
  const FILL_ORDER = W.SLOTS
    .filter(s => !(W.BUILD[s.id] && W.BUILD[s.id].empty))
    .sort((a, b) => (W.BUILD[a.id]?.fill ?? 99) - (W.BUILD[b.id]?.fill ?? 99))
    .map(s => s.id);

  const FILL_TIME = { subtle: 7000, energetic: 5200, cinematic: 8600 };
  const FINAL_MATCH = 96;

  function finalFor(t) {
    if (t._fromData && t.achieved != null) return t.achieved;
    if (t.kind === "exact") return t.target;
    return t.target > 0 ? Math.round(t.target * 1.5) : 150;
  }
  const easeOut = (x) => 1 - Math.pow(1 - x, 2);

  function App() {
    const [t, setTweak] = window.useTweaks(TWEAK_DEFAULTS);

    const [classSel, setClassSel] = useState("Cra");
    const [level, setLevel] = useState(110);
    const [minLevel, setMinLevel] = useState(80);
    const [mode, setMode] = useState("mast");
    const [targets, setTargets] = useState(initTargets);
    const [constraints, setConstraints] = useState({ maxRarity: "epic", duration: 20, stopAtMatch: false });
    const [forced, setForced] = useState([]);
    const [excluded, setExcluded] = useState([]);

    const [phase, setPhase] = useState("idle");
    const [progress, setProgress] = useState(0);
    const [match, setMatch] = useState(0);
    const [filledKeys, setFilledKeys] = useState(() => new Set());
    const [lastLanded, setLastLanded] = useState(null);
    const [achieved, setAchieved] = useState({});

    const [modal, setModal] = useState(null);        // "addstat" | {picker}
    const [tooltip, setTooltip] = useState(null);
    const [zenithState, setZenithState] = useState("idle");
    const [zenithError, setZenithError] = useState(false);
    const [toast, setToast] = useState(null);

    const timer = useRef(null);
    const landTimer = useRef(null);
    const zenithFailedOnce = useRef(false);

    // ---------- apply tweaks to :root (theme-neutral attrs; each stylesheet maps them) ----------
    useEffect(() => {
      const el = document.documentElement;
      el.dataset.accent = t.accent;
      el.dataset.display = t.displayFont;
      el.dataset.depth = t.depth;
      el.style.setProperty("--scale", (0.86 + (t.density - 1) * 0.065).toFixed(3));
    }, [t.accent, t.displayFont, t.density, t.depth]);

    // ---------- search simulation ----------
    const stop = useCallback(() => {
      if (timer.current) { clearInterval(timer.current); timer.current = null; }
    }, []);

    const runSearch = useCallback(() => {
      stop();
      setPhase("searching");
      setProgress(0); setMatch(0);
      setFilledKeys(new Set()); setLastLanded(null);
      setZenithError(false); zenithFailedOnce.current = false;

      const finals = {};
      targets.forEach(tg => { finals[tg.id] = finalFor(tg); });

      const N = 72;
      const total = FILL_TIME[t.fill] || 5200;
      const interval = total / N;
      const revealAt = {};
      FILL_ORDER.forEach((id, k) => { revealAt[id] = Math.round((k + 1) / (FILL_ORDER.length + 1) * N * 0.86); });

      let tick = 0;
      const shown = new Set();
      timer.current = setInterval(() => {
        tick++;
        const x = tick / N;
        const e = easeOut(Math.min(1, x));

        // reveal items whose threshold passed
        let landed = null;
        FILL_ORDER.forEach(id => {
          if (!shown.has(id) && tick >= revealAt[id]) { shown.add(id); landed = id; }
        });
        if (landed) {
          setFilledKeys(new Set(shown));
          setLastLanded(landed);
          if (landTimer.current) clearTimeout(landTimer.current);
          landTimer.current = setTimeout(() => setLastLanded(null), 560);
        }

        // climb meters (small jitter for "best so far" feel)
        const jit = tick < N ? (Math.random() * 2 - 1) : 0;
        setMatch(Math.max(0, Math.min(FINAL_MATCH, Math.round(FINAL_MATCH * e + jit))));
        setProgress(Math.min(100, Math.round(x * 100)));
        const ach = {};
        Object.keys(finals).forEach(id => {
          const fv = finals[id];
          const j = tick < N ? (1 + (Math.random() * 0.06 - 0.03)) : 1;
          ach[id] = Math.round(fv * e * j);
        });
        setAchieved(ach);

        if (tick >= N) {
          stop();
          setProgress(100); setMatch(FINAL_MATCH);
          setFilledKeys(new Set(FILL_ORDER));
          setAchieved(finals);
          setPhase("done");
        }
      }, interval);
    }, [targets, t.fill, stop]);

    const cancelSearch = useCallback(() => {
      stop();
      setPhase("idle");
      setProgress(0); setMatch(0);
      setFilledKeys(new Set()); setAchieved({});
    }, [stop]);

    useEffect(() => () => { stop(); if (landTimer.current) clearTimeout(landTimer.current); }, [stop]);

    // ---------- handlers ----------
    const onTargetVal = (id, v) => setTargets(ts => ts.map(x => x.id === id ? { ...x, target: v } : x));
    const onRemoveTarget = (id) => setTargets(ts => ts.filter(x => x.id !== id));
    const onPickStat = (s) => {
      const meta = { ap: ["AP", "#E08A3C"], mp: ["MP", "#3FC7B4"], range: ["◎", "#3FC7B4"], crit: ["%", "#E8C24A"], wp: ["WP", "#B07BD9"], hp: ["♥", "#E0584F"], lock: ["⊟", "#9AA0AC"], dodge: ["➶", "#9AA0AC"], init: ["⚡", "#E8C24A"] };
      const elc = { elemwater: "#4A9FE0", elemfire: "#E0584F", elemearth: "#7BB04F", elemair: "#9FB6C4", reswater: "#4A9FE0", resair: "#9FB6C4" };
      const g = meta[s.id] || ["◆", elc[s.id] || "#7BB04F"];
      setTargets(ts => [...ts, { id: s.id, label: s.label.replace(/\s*\(.*\)/, ""), short: s.id, kind: s.kind, target: s.kind === "exact" ? 1 : 100, glyph: g[0], color: g[1], unit: s.id === "crit" ? "%" : "" }]);
      setModal(null);
    };
    const onConstraint = (k, v) => setConstraints(c => ({ ...c, [k]: v }));
    const onAddItem = (kind, it) => {
      if (kind === "forced") setForced(f => f.find(i => i.fr === it.fr) ? f : [...f, it]);
      else setExcluded(f => f.find(i => i.fr === it.fr) ? f : [...f, it]);
    };
    const onRemoveItem = (kind, fr) => {
      if (kind === "forced") setForced(f => f.filter(i => i.fr !== fr));
      else setExcluded(f => f.filter(i => i.fr !== fr));
    };

    const showToast = (msg, ok) => { setToast({ msg, ok }); setTimeout(() => setToast(null), 2600); };

    const onZenith = () => {
      setZenithError(false);
      setZenithState("loading");
      setTimeout(() => {
        if (!zenithFailedOnce.current) {
          // first attempt fails — demonstrates the error state + recovery
          zenithFailedOnce.current = true;
          setZenithState("idle");
          setZenithError(true);
        } else {
          setZenithState("idle");
          showToast("Opened build in Zenith ↗", true);
        }
      }, 1300);
    };
    const onCopy = () => showToast("Build link copied to clipboard", true);

    const onHover = (slot, item, ev) => setTooltip({ slot, item, x: ev.clientX, y: ev.clientY });
    const onLeave = () => setTooltip(null);

    return (
      <div className="app">
        <C.TopBar classSel={classSel} level={level} minLevel={minLevel}
          phase={phase} progress={progress} match={match}
          onClass={setClassSel}
          onLevel={(k, v) => k === "level" ? setLevel(v) : setMinLevel(v)}
          onSearch={runSearch} onCancel={cancelSearch} />

        <div className="body">
          <C.RequestPanel mode={mode} onMode={setMode}
            targets={targets} onTargetVal={onTargetVal} onRemoveTarget={onRemoveTarget}
            onAddStat={() => setModal("addstat")}
            constraints={constraints} onConstraint={onConstraint}
            forced={forced} excluded={excluded}
            onOpenPicker={(kind) => setModal({ picker: kind })}
            onRemoveItem={onRemoveItem} />

          <C.Paperdoll phase={phase} build={W.BUILD} filledKeys={filledKeys}
            lastLanded={lastLanded} classSel={classSel} level={level}
            layout={t.layout === "ring" ? "" : t.layout}
            onHover={onHover} onLeave={onLeave} />

          <C.StatsPanel phase={phase} match={match} targets={targets} achieved={achieved}
            zenithState={zenithState} error={zenithError}
            onZenith={onZenith} onRetry={onZenith} onCopy={onCopy} />
        </div>

        <C.Tooltip data={tooltip} />

        {modal === "addstat" && <C.AddStatModal existing={targets} onPick={onPickStat} onClose={() => setModal(null)} />}
        {modal && modal.picker && <C.ItemPickerModal kind={modal.picker} forced={forced} excluded={excluded}
          onAdd={(it) => onAddItem(modal.picker, it)} onClose={() => setModal(null)} />}

        {toast && <div className="toast-wrap"><div className="toast">{toast.ok && <span className="ok">✓</span>}{toast.msg}</div></div>}

        <Tweaks t={t} setTweak={setTweak} />
      </div>
    );
  }

  // ---------- Tweaks panel ----------
  function Tweaks({ t, setTweak }) {
    const { TweaksPanel, TweakSection, TweakRadio, TweakSelect, TweakSlider } = window;
    return (
      <TweaksPanel>
        <TweakSection label="Identity" />
        <TweakRadio label="Accent" value={t.accent} options={["copper", "balanced", "teal"]} onChange={v => setTweak("accent", v)} />
        <TweakSelect label="Display font" value={t.displayFont} options={["industrial", "techy", "warm"]} onChange={v => setTweak("displayFont", v)} />
        <TweakRadio label="Surface depth" value={t.depth} options={["deep", "standard", "lifted"]} onChange={v => setTweak("depth", v)} />
        <TweakSection label="Layout" />
        <TweakSlider label="Density" value={t.density} min={1} max={5} step={1} onChange={v => setTweak("density", v)} />
        <TweakRadio label="Paperdoll" value={t.layout} options={["ring", "columns", "radial"]} onChange={v => setTweak("layout", v)} />
        <TweakSection label="Search" />
        <TweakRadio label="Live fill" value={t.fill} options={["subtle", "energetic", "cinematic"]} onChange={v => setTweak("fill", v)} />
      </TweaksPanel>
    );
  }

  ReactDOM.createRoot(document.getElementById("root")).render(<App />);
})();
