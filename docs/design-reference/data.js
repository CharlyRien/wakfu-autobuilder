/* ============================================================
   Wakfu Autobuilder — sample data for the redesign prototype
   All item names are invented (FR + EN) — swap for real engine data.
   Icons here are CSS/abstract placeholders for Ankama art.
   ============================================================ */
(function () {
  // ---- Rarity palette (placeholder tints; confirm vs assets/rarities/*.png) ----
  const RARITY = {
    common:    { label: "Common",    fr: "Commun",    color: "#B9BEC7" },
    unusual:   { label: "Unusual",   fr: "Inhabituel",color: "#6FBF73" },
    rare:      { label: "Rare",      fr: "Rare",      color: "#E0913C" },
    mythical:  { label: "Mythical",  fr: "Mythique",  color: "#D9A441" },
    legendary: { label: "Legendary", fr: "Légendaire",color: "#E8C24A" },
    relic:     { label: "Relic",     fr: "Relique",   color: "#E0683C" },
    epic:      { label: "Epic",      fr: "Épique",    color: "#B07BD9" },
    souvenir:  { label: "Souvenir",  fr: "Souvenir",  color: "#5FBFB0" },
  };

  // ---- Element palette (water/fire/earth/air) ----
  const ELEMENT = {
    water: { label: "Water", color: "#4A9FE0", glyph: "≈" },
    fire:  { label: "Fire",  color: "#E0584F", glyph: "▲" },
    earth: { label: "Earth", color: "#7BB04F", glyph: "◆" },
    air:   { label: "Air",   color: "#9FB6C4", glyph: "❖" },
  };

  // ---- 18 classes ----
  const CLASSES = [
    "Cra","Iop","Sram","Xelor","Eniripsa","Enutrof","Sadida","Osamodas",
    "Feca","Ecaflip","Pandawa","Sacrier","Rogue","Masqueraider","Foggernaut",
    "Eliotrope","Huppermage","Ouginak"
  ];

  // ---- The 14 equipment slots, with grid position + a glyph placeholder ----
  // glyph is a tiny abstract mark standing in for assets/itemTypes/<id>.png
  const SLOTS = [
    { id: "helmet",  label: "Helmet",        fr: "Casque",      glyph: "⛨", side: "L", order: 0 },
    { id: "amulet",  label: "Amulet",        fr: "Amulette",    glyph: "◌", side: "L", order: 1 },
    { id: "epaul",   label: "Epaulettes",    fr: "Épaulettes",  glyph: "▱", side: "L", order: 2 },
    { id: "chest",   label: "Breastplate",   fr: "Plastron",    glyph: "▢", side: "L", order: 3 },
    { id: "cape",    label: "Cape",          fr: "Cape",        glyph: "⊳", side: "L", order: 4 },
    { id: "emblem",  label: "Emblem",        fr: "Insigne",     glyph: "✦", side: "R", order: 0 },
    { id: "belt",    label: "Belt",          fr: "Ceinture",    glyph: "═", side: "R", order: 1 },
    { id: "ring1",   label: "Ring I",        fr: "Anneau I",    glyph: "◯", side: "R", order: 2 },
    { id: "ring2",   label: "Ring II",       fr: "Anneau II",   glyph: "◯", side: "R", order: 3 },
    { id: "boots",   label: "Boots",         fr: "Bottes",      glyph: "⊓", side: "R", order: 4 },
    { id: "weapon",  label: "Weapon",        fr: "Arme",        glyph: "⚔", side: "B", order: 0 },
    { id: "weapon2", label: "Second Wpn",    fr: "Seconde arme",glyph: "⛉", side: "B", order: 1 },
    { id: "pet",     label: "Pet",           fr: "Familier",    glyph: "❀", side: "B", order: 2 },
    { id: "mount",   label: "Mount",         fr: "Monture",     glyph: "≋", side: "B", order: 3 },
  ];

  // ---- The discovered build (the engine's BuildCombination, mocked) ----
  // 'fill' = step index at which this item lands during the live search.
  const BUILD = {
    helmet:  { fr:"Capuche du Tireur d'élite", en:"Sharpshooter's Hood",   rarity:"legendary", lvl:110, fill:3, stats:[["Distance Mastery",65,"earth"],["AP",1],["HP",180]] },
    amulet:  { fr:"Collier de Visée",          en:"Aiming Pendant",        rarity:"mythical",  lvl:108, fill:5, stats:[["Critical Mastery",48],["Crit",4],["Range",1]] },
    epaul:   { fr:"Ailerons du Vent",          en:"Windfin Pauldrons",     rarity:"rare",      lvl:105, fill:7, stats:[["Distance Mastery",52,"earth"],["MP",1]] },
    chest:   { fr:"Plastron du Sniper",        en:"Sniper's Plastron",     rarity:"legendary", lvl:110, fill:2, stats:[["HP",260],["Distance Mastery",58,"earth"],["Resist Air",30,"air"]] },
    cape:    { fr:"Cape de Longue-Vue",        en:"Spyglass Cape",         rarity:"mythical",  lvl:107, fill:6, stats:[["Distance Mastery",60,"earth"],["Crit",3],["Dodge",24]] },
    emblem:  { fr:"Insigne du Guetteur",       en:"Watcher's Emblem",      rarity:"epic",      lvl:110, fill:9, stats:[["Critical Mastery",40],["Range",1]] },
    belt:    { fr:"Ceinture de Précision",     en:"Precision Belt",        rarity:"rare",      lvl:104, fill:8, stats:[["HP",150],["Distance Mastery",44,"earth"],["Lock",30]] },
    ring1:   { fr:"Anneau du Viseur",          en:"Sight Ring",            rarity:"mythical",  lvl:106, fill:10, stats:[["Crit",5],["Critical Mastery",36],["AP",0]] },
    ring2:   { fr:"Anneau de la Flèche",       en:"Arrow Ring",            rarity:"rare",      lvl:103, fill:11, stats:[["Distance Mastery",40,"earth"],["MP",1]] },
    boots:   { fr:"Bottes du Marcheur",        en:"Strider Boots",         rarity:"unusual",   lvl:100, fill:4, stats:[["MP",1],["Distance Mastery",36,"earth"],["Dodge",20]] },
    weapon:  { fr:"Arc-tonnerre Sylvestre",    en:"Sylvan Thunderbow",     rarity:"relic",     lvl:110, fill:1, stats:[["Distance Mastery",95,"earth"],["AP",2],["Range",1],["Crit",6]] },
    weapon2: { fr:"—",                         en:"—",                     rarity:"common",    lvl:0,   fill:99, empty:true },
    pet:     { fr:"Wabbit Vigilant",           en:"Watchful Wabbit",       rarity:"epic",      lvl:110, fill:12, stats:[["Distance Mastery",50,"earth"]] },
    mount:   { fr:"Dragodinde Céleste",        en:"Celestial Dragoturkey", rarity:"legendary", lvl:110, fill:13, stats:[["HP",200],["Distance Mastery",30,"earth"],["MP",1]] },
  };

  // ---- Target stats the user requested (left panel + right panel) ----
  // kind: "exact" => must hit; "max" => maximize above threshold
  const TARGETS = [
    { id:"ap",    label:"AP",                short:"AP",   kind:"exact", target:11,  achieved:11,   glyph:"AP", color:"#E08A3C" },
    { id:"mp",    label:"MP",                short:"MP",   kind:"exact", target:4,   achieved:4,    glyph:"MP", color:"#3FC7B4" },
    { id:"range", label:"Range",             short:"Ra",  kind:"exact", target:4,   achieved:4,    glyph:"◎",  color:"#3FC7B4" },
    { id:"crit",  label:"Critical Hit",      short:"Cr",  kind:"exact", target:25,  achieved:31,   glyph:"%",  color:"#E8C24A", unit:"%" },
    { id:"distm", label:"Distance Mastery",  short:"Dist",kind:"max",   target:500, achieved:1287, glyph:"◆",  color:"#7BB04F" },
    { id:"critm", label:"Critical Mastery",  short:"CrM", kind:"max",   target:0,   achieved:412,  glyph:"✷",  color:"#E8C24A" },
    { id:"hp",    label:"Health Points",     short:"HP",  kind:"max",   target:2000,achieved:3148, glyph:"♥",  color:"#E0584F" },
    { id:"resair",label:"Resist · Air",      short:"RAir",kind:"max",   target:0,   achieved:186,  glyph:"❖",  color:"#9FB6C4" },
    { id:"dodge", label:"Dodge",             short:"Dge", kind:"max",   target:0,   achieved:128,  glyph:"➶",  color:"#9AA0AC" },
  ];

  // ---- Skill branches (5) with assigned/maximum points ----
  const SKILLS = [
    { id:"intel",  label:"Intelligence", color:"#7BB04F", points:50, max:50, lines:[
      ["% Health Points", 10, 10], ["Elemental Resistance", 20, 20], ["Barrier", 0, 10], ["% Heals Received", 0, 10] ]},
    { id:"strength", label:"Strength", color:"#E0584F", points:50, max:50, lines:[
      ["Elemental Mastery", 40, 40], ["Health Points", 10, 10] ]},
    { id:"agility", label:"Agility", color:"#3FC7B4", points:50, max:50, lines:[
      ["Lock", 0, 20], ["Dodge", 20, 20], ["Initiative", 10, 10], ["Force of Will", 20, 20] ]},
    { id:"fortune", label:"Fortune", color:"#E8C24A", points:50, max:50, lines:[
      ["% Critical Hit", 20, 20], ["% Block", 0, 20], ["Critical Mastery", 20, 20], ["Rear Mastery", 10, 20] ]},
    { id:"major", label:"Major", color:"#E08A3C", points:12, max:12, lines:[
      ["Action Point", 4, 4], ["MP & Damage", 4, 4], ["Range & Damage", 4, 4], ["Control", 0, 4] ]},
  ];

  // ---- Item DB for the forced/excluded picker (search matches FR name) ----
  const ITEM_DB = [
    { fr:"Arc-tonnerre Sylvestre", en:"Sylvan Thunderbow", rarity:"relic", lvl:110, slot:"weapon" },
    { fr:"Capuche du Tireur d'élite", en:"Sharpshooter's Hood", rarity:"legendary", lvl:110, slot:"helmet" },
    { fr:"Plastron du Sniper", en:"Sniper's Plastron", rarity:"legendary", lvl:110, slot:"chest" },
    { fr:"Collier de Visée", en:"Aiming Pendant", rarity:"mythical", lvl:108, slot:"amulet" },
    { fr:"Anneau du Viseur", en:"Sight Ring", rarity:"mythical", lvl:106, slot:"ring1" },
    { fr:"Cape de Longue-Vue", en:"Spyglass Cape", rarity:"mythical", lvl:107, slot:"cape" },
    { fr:"Bottes du Marcheur", en:"Strider Boots", rarity:"unusual", lvl:100, slot:"boots" },
    { fr:"Ceinture de Précision", en:"Precision Belt", rarity:"rare", lvl:104, slot:"belt" },
    { fr:"Ailerons du Vent", en:"Windfin Pauldrons", rarity:"rare", lvl:105, slot:"epaul" },
    { fr:"Dragodinde Céleste", en:"Celestial Dragoturkey", rarity:"legendary", lvl:110, slot:"mount" },
    { fr:"Wabbit Vigilant", en:"Watchful Wabbit", rarity:"epic", lvl:110, slot:"pet" },
    { fr:"Épée du Brûlot", en:"Firebrand Sword", rarity:"legendary", lvl:110, slot:"weapon" },
    { fr:"Dague de l'Ombre", en:"Shadow Dagger", rarity:"rare", lvl:108, slot:"weapon2" },
    { fr:"Bouclier du Rempart", en:"Rampart Shield", rarity:"mythical", lvl:110, slot:"weapon2" },
    { fr:"Coiffe du Sage", en:"Sage's Headdress", rarity:"epic", lvl:110, slot:"helmet" },
    { fr:"Anneau de la Flèche", en:"Arrow Ring", rarity:"rare", lvl:103, slot:"ring2" },
    { fr:"Insigne du Guetteur", en:"Watcher's Emblem", rarity:"epic", lvl:110, slot:"emblem" },
    { fr:"Amulette du Crépuscule", en:"Twilight Amulet", rarity:"legendary", lvl:110, slot:"amulet" },
    { fr:"Bottes de Sept Lieues", en:"Seven-League Boots", rarity:"epic", lvl:110, slot:"boots" },
    { fr:"Ceinture du Colosse", en:"Colossus Belt", rarity:"legendary", lvl:110, slot:"belt" },
  ];

  // ---- Stat catalogue for the "add target stat" editor ----
  const STAT_CATALOG = [
    { id:"ap", label:"Action Point (AP)", kind:"exact" },
    { id:"mp", label:"Movement Point (MP)", kind:"exact" },
    { id:"range", label:"Range", kind:"exact" },
    { id:"crit", label:"Critical Hit %", kind:"exact" },
    { id:"wp", label:"Wakfu Point (WP)", kind:"exact" },
    { id:"distm", label:"Distance Mastery", kind:"max" },
    { id:"meleem", label:"Melee Mastery", kind:"max" },
    { id:"critm", label:"Critical Mastery", kind:"max" },
    { id:"rearm", label:"Rear Mastery", kind:"max" },
    { id:"berserkm", label:"Berserk Mastery", kind:"max" },
    { id:"healm", label:"Healing Mastery", kind:"max" },
    { id:"elemwater", label:"Water Mastery", kind:"max" },
    { id:"elemfire", label:"Fire Mastery", kind:"max" },
    { id:"elemearth", label:"Earth Mastery", kind:"max" },
    { id:"elemair", label:"Air Mastery", kind:"max" },
    { id:"hp", label:"Health Points", kind:"max" },
    { id:"lock", label:"Lock", kind:"max" },
    { id:"dodge", label:"Dodge", kind:"max" },
    { id:"resair", label:"Resist · Air", kind:"max" },
    { id:"reswater", label:"Resist · Water", kind:"max" },
    { id:"init", label:"Initiative", kind:"max" },
  ];

  window.WAKFU = { RARITY, ELEMENT, CLASSES, SLOTS, BUILD, TARGETS, SKILLS, ITEM_DB, STAT_CATALOG };
})();
