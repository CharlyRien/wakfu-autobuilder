/* ============================================================
   Wakfu Autobuilder — result UI: paperdoll, stats, skills, modals
   ============================================================ */
(function () {
  const { useState, useMemo } = React;
  const W = window.WAKFU;

  const fmt = (n) => n >= 1000 ? n.toLocaleString("en-US") : "" + n;

  // ---------- a single equipment slot ----------
  function Slot({ slot, item, justLanded, rightAlign, onHover, onLeave }) {
    const filled = item && !item.empty;
    const rarity = filled ? W.RARITY[item.rarity] : null;
    const cls = ["slot", filled ? "filled" : "empty-slot", justLanded ? "justlanded" : "", rightAlign ? "right-align" : ""].join(" ");
    return (
      <div className={cls}
           style={rarity ? { "--rarity": rarity.color } : undefined}
           onMouseEnter={filled ? (e) => onHover(slot, item, e) : undefined}
           onMouseMove={filled ? (e) => onHover(slot, item, e) : undefined}
           onMouseLeave={onLeave}>
        <div className="slot-icon">
          <span className="ph-glyph">{slot.glyph}</span>
          {rarity && <span className="rarity-badge" style={{ background: rarity.color }}></span>}
        </div>
        <div className="slot-meta">
          <div className="slot-type">{slot.label}</div>
          <div className="slot-name">{filled ? item.fr : "empty"}</div>
          {filled && <div className="slot-lvl">Lv {item.lvl} · {rarity.label}</div>}
        </div>
      </div>
    );
  }

  // ---------- paperdoll ----------
  function Paperdoll({ phase, build, filledKeys, lastLanded, classSel, level, layout, onHover, onLeave }) {
    const idle = phase === "idle";
    const searching = phase === "searching";
    const left  = W.SLOTS.filter(s => s.side === "L").sort((a, b) => a.order - b.order);
    const right = W.SLOTS.filter(s => s.side === "R").sort((a, b) => a.order - b.order);
    const rail  = W.SLOTS.filter(s => s.side === "B").sort((a, b) => a.order - b.order);

    const slotEl = (s, ra) => {
      const visible = filledKeys.has(s.id);
      const item = visible ? build[s.id] : null;
      return <Slot key={s.id} slot={s} item={item} rightAlign={ra}
                   justLanded={lastLanded === s.id}
                   onHover={onHover} onLeave={onLeave} />;
    };

    const charArt = (
      <div className={"char-art " + (searching ? "searching" : "")}>
        <div className="ca-frame"></div>
        <div style={{ textAlign: "center", zIndex: 1 }}>
          <div className="ca-class">{classSel}</div>
          <div className="ca-sub">Level {level}</div>
          <div className="ca-label" style={{ marginTop: 14 }}>{ "{ character art }" }</div>
        </div>
      </div>
    );

    const head = (
      <div className="zone-head"><span className="dot"></span><h2>Discovered Build</h2>
        <span className="hint">{idle ? "Awaiting search" : searching ? "Live · best so far" : "Result"}</span>
      </div>
    );
    const foot = <div className="disclaimer">Unofficial fan tool · not affiliated with Ankama · item art © Ankama (community-sourced)</div>;

    if (layout === "radial") {
      // 10 equipment slots placed evenly around the central character
      const ring = [...left, ...right];
      return (
        <div className="col">
          {head}
          <div className="doll-wrap">
            <div className={"doll-radial " + (idle ? "idle" : "")}>
              <div className="radial-center">{charArt}</div>
              {ring.map((s, i) => {
                const ang = (-90 + i * (360 / ring.length)) * Math.PI / 180;
                return (
                  <div className="radial-slot" key={s.id}
                       style={{ left: `calc(50% + ${Math.cos(ang) * 40}% )`, top: `calc(42% + ${Math.sin(ang) * 33}%)` }}>
                    {slotEl(s, false)}
                  </div>
                );
              })}
              <div className="doll-rail radial-rail">{rail.map(s => slotEl(s, false))}</div>
            </div>
          </div>
          {foot}
        </div>
      );
    }

    return (
      <div className="col">
        {head}
        <div className="doll-wrap">
          <div className={"doll " + layout + " " + (idle ? "idle" : "")}>
            <div className="doll-col left">{left.map(s => slotEl(s, false))}</div>
            <div className="doll-center">{charArt}</div>
            <div className="doll-col right">{right.map(s => slotEl(s, true))}</div>
            <div className="doll-rail">{rail.map(s => slotEl(s, false))}</div>
          </div>
        </div>
        {foot}
      </div>
    );
  }

  // ---------- stats panel (right) ----------
  function StatRow({ t, achieved, mode }) {
    const exact = t.kind === "exact";
    let status, scls;
    if (exact) {
      if (achieved >= t.target) { status = "✓"; scls = "ok"; }
      else { status = "▾"; scls = "miss"; }
    } else {
      if (t.target > 0 && achieved >= t.target) { status = "✓"; scls = "ok"; }
      else if (t.target > 0) { status = "▾"; scls = "miss"; }
      else { status = "↑"; scls = "over"; }
    }
    const pct = t.target > 0 ? Math.min(100, Math.round(achieved / t.target * 100)) : 100;
    return (
      <div className="srow">
        <span className="glyph" style={{ width: 24, height: 24, color: t.color, borderColor: "color-mix(in oklab," + t.color + " 35%, var(--border))" }}>{t.glyph}</span>
        <span className="name">{t.label}<small>{exact ? "exact" : "maximize"}</small></span>
        <span className="vals">
          <span className="got" style={{ color: scls === "miss" ? "var(--warning)" : "var(--text)" }}>{fmt(achieved)}{t.unit || ""}</span>
          {t.target > 0 && <><span className="sep">/</span><span className="tgt">{fmt(t.target)}{t.unit || ""}</span></>}
        </span>
        <span className={"status " + scls}>{status}</span>
        {t.target > 0 && <span className="ministretch"><i style={{ width: pct + "%", background: scls === "miss" ? "var(--warning)" : (scls === "over" ? "var(--teal)" : "var(--success)") }}></i></span>}
      </div>
    );
  }

  function SkillTree() {
    return (
      <div className="card">
        <div className="card-title">Skill Allocation <span className="ti-line"></span><span style={{ color: "var(--faint)", fontFamily: "var(--mono)" }}>5 branches</span></div>
        {W.SKILLS.map(b => (
          <div className="skill" key={b.id}>
            <div className="skill-head">
              <span className="sdot" style={{ background: b.color }}></span>
              <span className="slabel">{b.label}</span>
              <span className="spts">{b.points}/{b.max}</span>
            </div>
            <div className="skill-lines">
              {b.lines.map((l, i) => (
                <div className={"sl " + (l[1] > 0 ? "used" : "unused")} key={i}>
                  <span>{l[0]}</span><span className="v">{l[1]}/{l[2]}</span>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    );
  }

  function StatsPanel({ phase, match, targets, achieved, zenithState, onZenith, onCopy, error, onRetry }) {
    const idle = phase === "idle";
    const done = phase === "done";
    return (
      <div className="col">
        <div className="zone-head"><span className="dot"></span><h2>Resulting Stats</h2><span className="hint">Output</span></div>
        <div className="col-scroll">

          <div className="card">
            <div className="match-hero">
              <div className={"match-num " + (match >= 100 ? "" : "partial")}>{match}<small>%</small></div>
              <div className="match-cap">Build Match</div>
              <div className="bar match match-bar"><i style={{ width: match + "%" }}></i></div>
            </div>
          </div>

          {idle ? (
            <div className="card">
              <div className="empty-hint">
                <span className="big">No build yet</span>
                Set your target stats &amp; constraints on the left,<br />then hit <b style={{ color: "var(--accent)" }}>Search</b>. The paperdoll fills in live as better builds are found.
              </div>
            </div>
          ) : (
            <>
              <div className="card">
                <div className="card-title">Desired vs Achieved <span className="ti-line"></span></div>
                {targets.map(t => <StatRow key={t.id} t={t} achieved={achieved[t.id] ?? 0} />)}
              </div>

              <SkillTree />

              <div className="card">
                {error && (
                  <div className="err-banner" style={{ marginBottom: 10 }}>
                    <span className="ico">⚠</span>
                    <span>Couldn't reach Zenith to build a share link. Check your connection.</span>
                    <button className="retry" onClick={onRetry}>Retry</button>
                  </div>
                )}
                <div className="actions">
                  <button className="btn-zenith" disabled={!done || zenithState === "loading"} onClick={onZenith}>
                    {zenithState === "loading" ? <><span className="spin"></span> Opening…</> : <>Open in Zenith ↗</>}
                  </button>
                  <button className="btn-ghost" disabled={!done} onClick={onCopy}>⧉ Copy build link</button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    );
  }

  // ---------- tooltip ----------
  function Tooltip({ data }) {
    if (!data) return null;
    const { slot, item, x, y } = data;
    const rarity = W.RARITY[item.rarity];
    const left = Math.min(x + 16, window.innerWidth - 256);
    const top = Math.min(y + 14, window.innerHeight - 200);
    return (
      <div className="tooltip" style={{ left, top }}>
        <div className="tt-name">{item.fr}</div>
        <div className="tt-en">{item.en} · {slot.label}</div>
        <div className="tt-meta">
          <span className="tt-rarity" style={{ background: "color-mix(in oklab," + rarity.color + " 22%, transparent)", color: rarity.color, border: "1px solid color-mix(in oklab," + rarity.color + " 45%, transparent)" }}>{rarity.label}</span>
          <span className="tt-lvl">Lv {item.lvl}</span>
        </div>
        {(item.stats || []).map((s, i) => {
          const el = s[2] ? W.ELEMENT[s[2]] : null;
          return (
            <div className="tt-stat" key={i}>
              {el && <span className="el" style={{ background: el.color }}></span>}
              <span style={{ fontFamily: "var(--mono)", color: "var(--accent)" }}>{s[1] >= 0 ? "+" : ""}{s[1]}</span>
              <span style={{ color: "var(--muted)" }}>{s[0]}</span>
            </div>
          );
        })}
      </div>
    );
  }

  // ---------- modal: add target stat ----------
  function AddStatModal({ existing, onPick, onClose }) {
    const [q, setQ] = useState("");
    const have = new Set(existing.map(t => t.id));
    const list = W.STAT_CATALOG.filter(s => s.label.toLowerCase().includes(q.toLowerCase()));
    return (
      <div className="overlay" onMouseDown={onClose}>
        <div className="modal" onMouseDown={e => e.stopPropagation()}>
          <div className="modal-head"><h3>Add Target Stat</h3><button className="x" onClick={onClose}>×</button></div>
          <div className="modal-body">
            <div className="search-field"><span className="ico">⌕</span><input autoFocus placeholder="Filter characteristics…" value={q} onChange={e => setQ(e.target.value)} /></div>
            <div className="cat-grid">
              {list.map(s => {
                const dis = have.has(s.id);
                return (
                  <button key={s.id} className={"cat-item " + (dis ? "dis" : "")} disabled={dis} onClick={() => !dis && onPick(s)}>
                    <span>{s.label}</span>
                    <span className="ck">{dis ? "added" : s.kind}</span>
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ---------- modal: forced / excluded item picker ----------
  function ItemPickerModal({ kind, forced, excluded, onAdd, onClose }) {
    const [q, setQ] = useState("");
    const taken = new Set([...forced, ...excluded].map(i => i.fr));
    const list = useMemo(() => {
      const t = q.toLowerCase();
      return W.ITEM_DB.filter(i => i.fr.toLowerCase().includes(t) || i.en.toLowerCase().includes(t));
    }, [q, forced, excluded]);
    return (
      <div className="overlay" onMouseDown={onClose}>
        <div className="modal" onMouseDown={e => e.stopPropagation()}>
          <div className="modal-head">
            <h3>{kind === "forced" ? "Require an Item" : "Ban an Item"}</h3>
            <button className="x" onClick={onClose}>×</button>
          </div>
          <div className="modal-body">
            <div className="search-field"><span className="ico">⌕</span><input autoFocus placeholder="Search by name (FR or EN)…" value={q} onChange={e => setQ(e.target.value)} /></div>
            <div className="db-list">
              {list.map(it => {
                const used = taken.has(it.fr);
                const r = W.RARITY[it.rarity];
                return (
                  <div className="db-item" key={it.fr}>
                    <div className="db-icon" style={{ color: r.color }}>◆<span className="rarity-badge" style={{ background: r.color }}></span></div>
                    <div className="db-name">{it.fr}<small>{it.en}</small></div>
                    <div className="db-meta">
                      <span className="rarity-tag" style={{ background: "color-mix(in oklab," + r.color + " 20%, transparent)", color: r.color }}>{r.label}</span>
                      <span className="db-slot">Lv {it.lvl}</span>
                      {used ? <span className="db-slot" style={{ color: "var(--faint)" }}>added</span>
                            : <button className={kind === "forced" ? "add-f" : "add-e"} onClick={() => onAdd(it)}>{kind === "forced" ? "Require" : "Ban"}</button>}
                    </div>
                  </div>
                );
              })}
              {list.length === 0 && <div className="empty-hint">No items match “{q}”.</div>}
            </div>
          </div>
          <div className="modal-foot"><button className="btn-ghost" style={{ padding: "8px 16px" }} onClick={onClose}>Done</button></div>
        </div>
      </div>
    );
  }

  window.WBComp = Object.assign(window.WBComp || {}, { Paperdoll, StatsPanel, Tooltip, AddStatModal, ItemPickerModal });
})();
