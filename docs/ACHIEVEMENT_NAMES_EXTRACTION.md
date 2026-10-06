# Equip-criterion achievement names

`achievement-names.json` is a display-only map from achievement id to FR/EN/ES/PT names. It contains exactly
those achievements referenced by `IsAchievementComplete` atoms in `item-criteria.json` (including negated
atoms). With the current data that is one entry, 1509: **L'Effet Méryde / The Meridian Effect**.

`AchievementNames` finds everything structurally in the local client:

- The table-type enum is identified by `ACHIEVEMENT`, `ITEM`, `ITEM_SET`, `MONSTER`, `SPELL`, `STATE`.
  Its `ACHIEVEMENT` constructor carries the table id. A binary-data class must return this enum constant.
- `SchemaGenerator` derives the table's full wire schema from the client bytecode and selects the layout
  that decodes cleanly. `loadTable` enforces every record's indexed byte size; every record's leading int
  must also equal its index id. Every requested achievement must exist in the decoded table.
- The achievement UI model is identified by the public field keys `achievementId`, `isCompleted`,
  `isFollowed` and its `getName(): String` method. This method must invoke a string lookup with signature
  `(int namespace, long id, Object[] arguments)` and contain one distinct positive integer constant:
  the name namespace. Ambiguity or a changed lookup shape fails extraction.
- `I18nBundle` reads the exact `texts_<lang>.properties` entry of each local language jar. All four
  requested names must exist and be nonblank; no silent substitution is allowed.

Evidence in the local 1.93 client: the table enum is `eED`, whose `ACHIEVEMENT` constant is `oWo`,
with id **1**. `ber.getName()` calls `aTF.a(int,long,Object[])` with namespace **62** and its id field.
These obfuscated names are evidence only; none is used in extraction code.

```text
ber.getName():
  18: invokestatic  aTF.cWR:()LaTF;
  21: bipush        62
  23: aload_0
  24: getfield      hNE:I
  27: i2l
  28: iconst_0
  29: anewarray     java/lang/Object
  32: invokevirtual aTF.a:(IJ[Ljava/lang/Object;)Ljava/lang/String;
```

The normal `scripts/update-game-data.sh` bdata step writes this artifact after the fresh item criteria.
For a name-only regeneration without CDN fetches:

```sh
./gradlew :bdata-extractor:run --args="--achievement-names-only /Applications/Ankama/Wakfu"
```

`AchievementNamesDecodeTest` is install-gated and checks exact reproduction. The committed-data lock
runs in CI as `EmbeddedAchievementNamesDataTest`; GUI formatter tests cover named, negated and missing-id
conditions in both languages. Achievements remain assumed satisfied by the search; no engine or
certificate version bump is needed.
