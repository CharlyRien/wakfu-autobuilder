/* ============================================================
   Wakfu Autobuilder — core UI: top bar, request panel, shared bits
   ============================================================ */
(function () {
  const { useState, useRef, useEffect } = React;
  const W = window.WAKFU;

  // ---------- small reusable dropdown ----------
  function Dropdown({ trigger, children, align = "left", width }) {
    const [open, setOpen] = useState(false);
    const [pos, setPos] = useState(null);
    const ref = useRef(null);
    useEffect(() => {
      if (!open) return;
      const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
      document.addEventListener("mousedown", onDoc);
      return () => document.removeEventListener("mousedown", onDoc);
    }, [open]);
    const toggle = (e) => {
      const r = e.currentTarget.getBoundingClientRect();
      setPos({ left: align === "right" ? r.right - (width || 180) : r.left, top: r.bottom + 6, width: width || Math.max(r.width, 160) });
      setOpen(o => !o);
    };
    return (
      <span ref={ref} style={{ display: "inline-flex" }}>
        {trigger(toggle, open)}
        {open && pos && (
          <div className="menu" style={{ left: pos.left, top: pos.top, minWidth: pos.width }}>
            {children(() => setOpen(false))}
          </div>
        )}
      </span>
    );
  }

  // ---------- TOP BAR ----------
  function TopBar({ classSel, level, minLevel, phase, progress, match, onClass, onLevel, onSearch, onCancel }) {
    const searching = phase === "searching";
    return (
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true"></div>
          <div>
            <div className="brand-name">Wakfu <b>Autobuilder</b></div>
            <div className="brand-sub">Build Discoverer</div>
          </div>
        </div>

        <Dropdown width={180} trigger={(toggle, open) => (
          <button className="ctl" onClick={toggle}>
            <span className="ctl-label">Class</span>
            <span className="ctl-strong">{classSel}</span>
            <span className="caret">▾</span>
          </button>
        )}>
          {(close) => W.CLASSES.map(c => (
            <button key={c} className={c === classSel ? "on" : ""} onClick={() => { onClass(c); close(); }}>{c}</button>
          ))}
        </Dropdown>

        <div className="level-box">
          <label>Lvl</label>
          <input type="number" min="1" max="245" value={level} onChange={e => onLevel("level", +e.target.value)} />
          <label style={{ opacity: .7 }}>min</label>
          <input type="number" min="1" max={level} value={minLevel} onChange={e => onLevel("minLevel", +e.target.value)} />
        </div>

        <div className="topbar-spacer"></div>

        <div className="top-meters">
          <div className="tm-block">
            <div className="tm-row">
              <span className="tm-label">Progress</span>
              <span className="tm-val" style={{ color: "var(--accent-2)" }}>{progress}%</span>
            </div>
            <div className="bar progress"><i style={{ width: progress + "%" }}></i></div>
          </div>
          <div className="tm-block">
            <div className="tm-row">
              <span className="tm-label">Match</span>
              <span className="tm-val" style={{ color: match >= 100 ? "var(--success)" : "var(--warning)" }}>{match}%</span>
            </div>
            <div className="bar match"><i style={{ width: match + "%" }}></i></div>
          </div>
        </div>

        {searching
          ? <button className="search-btn is-cancel" onClick={onCancel}><span className="spin"></span> Stop</button>
          : <button className="search-btn" onClick={onSearch}>⟳ Search</button>}
      </header>
    );
  }

  // ---------- REQUEST PANEL ----------
  function RequestPanel(p) {
    const { mode, onMode, targets, onTargetVal, onRemoveTarget, onAddStat,
            constraints, onConstraint, forced, excluded, onOpenPicker, onRemoveItem } = p;
    return (
      <div className="col">
        <div className="zone-head"><span className="dot"></span><h2>Request</h2><span className="hint">Input</span></div>
        <div className="col-scroll">

          <div className="card">
            <div className="card-title">Search Mode <span className="ti-line"></span></div>
            <div className="seg">
              <button className={mode === "mast" ? "on" : ""} onClick={() => onMode("mast")}>
                Most Masteries<span className="sub">hit AP/MP/Ra/Crit, max masteries</span>
              </button>
              <button className={mode === "prec" ? "on" : ""} onClick={() => onMode("prec")}>
                Precision<span className="sub">hit every target exactly</span>
              </button>
            </div>
          </div>

          <div className="card">
            <div className="card-title">Target Stats <span className="ti-line"></span><span style={{ color: "var(--faint)", fontFamily: "var(--mono)" }}>{targets.length}</span></div>
            {targets.map(t => (
              <div className="tstat" key={t.id}>
                <span className="glyph" style={{ color: t.color, borderColor: "color-mix(in oklab," + t.color + " 35%, var(--border))" }}>{t.glyph}</span>
                <span>
                  <div className="name">{t.label}</div>
                  <div className="kind">{t.kind === "exact" ? "exact target" : "maximize ≥"}</div>
                </span>
                <input className="num" type="number" value={t.target}
                       onChange={e => onTargetVal(t.id, +e.target.value)} />
                <button className="rm" title="Remove" onClick={() => onRemoveTarget(t.id)}>×</button>
              </div>
            ))}
            <button className="add-stat" onClick={onAddStat}>＋ Add target stat</button>
          </div>

          <div className="card">
            <div className="card-title">Constraints <span className="ti-line"></span></div>
            <div className="crow">
              <span className="lab">Max rarity</span>
              <Dropdown align="right" width={150} trigger={(toggle) => (
                <button className="pill-select" onClick={toggle}>
                  <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                    <span className="rarity-badge-sm" style={{ background: W.RARITY[constraints.maxRarity].color }}></span>
                    {W.RARITY[constraints.maxRarity].label} ▾
                  </span>
                </button>
              )}>
                {(close) => Object.keys(W.RARITY).map(k => (
                  <button key={k} className={k === constraints.maxRarity ? "on" : ""} onClick={() => { onConstraint("maxRarity", k); close(); }}>
                    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                      <span style={{ width: 9, height: 9, borderRadius: 3, background: W.RARITY[k].color, display: "inline-block" }}></span>
                      {W.RARITY[k].label}
                    </span>
                  </button>
                ))}
              </Dropdown>
            </div>
            <div className="crow">
              <span className="lab">Search duration <small>genetic search window</small></span>
              <div className="level-box"><input type="number" min="2" max="120" value={constraints.duration} onChange={e => onConstraint("duration", +e.target.value)} /><label>sec</label></div>
            </div>
            <div className="crow">
              <span className="lab">Stop at 100% match</span>
              <Toggle on={constraints.stopAtMatch} onClick={() => onConstraint("stopAtMatch", !constraints.stopAtMatch)} />
            </div>
          </div>

          <div className="card">
            <div className="card-title">Forced Items <span className="ti-line"></span></div>
            <div className="chip-input">
              {forced.map(it => (
                <span className="chip forced" key={it.fr}><span style={{ width: 7, height: 7, borderRadius: 2, background: W.RARITY[it.rarity].color, display: "inline-block" }}></span>{it.fr}<span className="x" onClick={() => onRemoveItem("forced", it.fr)}>×</span></span>
              ))}
              <button className="mini-add" onClick={() => onOpenPicker("forced")}>＋ require item</button>
            </div>
          </div>

          <div className="card">
            <div className="card-title">Excluded Items <span className="ti-line"></span></div>
            <div className="chip-input">
              {excluded.map(it => (
                <span className="chip excl" key={it.fr}><span style={{ width: 7, height: 7, borderRadius: 2, background: W.RARITY[it.rarity].color, display: "inline-block" }}></span>{it.fr}<span className="x" onClick={() => onRemoveItem("excluded", it.fr)}>×</span></span>
              ))}
              <button className="mini-add" onClick={() => onOpenPicker("excluded")}>＋ ban item</button>
            </div>
          </div>

        </div>
      </div>
    );
  }

  function Toggle({ on, onClick }) {
    return (
      <button onClick={onClick} aria-pressed={on} style={{
        width: 42, height: 24, borderRadius: 13, border: "1px solid var(--border)", cursor: "pointer",
        background: on ? "var(--accent)" : "#14171c", position: "relative", transition: "background .15s", flex: "none"
      }}>
        <span style={{
          position: "absolute", top: 2, left: on ? 20 : 2, width: 18, height: 18, borderRadius: "50%",
          background: on ? "#1a1206" : "#5a626f", transition: "left .15s"
        }}></span>
      </button>
    );
  }

  window.WBComp = Object.assign(window.WBComp || {}, { Dropdown, TopBar, RequestPanel, Toggle });
})();
