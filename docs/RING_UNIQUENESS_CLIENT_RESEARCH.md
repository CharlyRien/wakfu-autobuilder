# HasAnotherSameEquipment: local-client evidence

Investigated 2026-10-06 against `/Applications/Ankama/Wakfu/lib/wakfu-client.jar`, data version
**1.93.1.62**. Jar SHA-256:
`e3eb8b9b6a1fa0d42d2f06f0af3e841ed783b2998f5c81d3cd8312294106d3cd`.

**Verdict:** the criterion compares the **item definition/reference id**, which is the Item table id
and the autobuilder's `equipmentId`. It excludes the candidate's own **instance id**. It does not
compare a localized name, rarity or family. Two same-name rings with different definition ids are
therefore not excluded by this generic criterion; their other criteria can still prohibit the pair.
This is a client-code finding, not a claim that every such pair passes every in-game condition.

The engine remains unchanged: `WakfuBuildSolver.addBuildValidityConstraints` still groups rings by
`name.fr.lowercase()` and enforces at most one per name. Changing that rule, its domination filter
and certificates belongs in a separate reviewed change, with the required engine/certifier version
bumps and soundness locks.

## Finding the implementation

The string `HasAnotherSameEquipment` occurs in the generated lexer **`eQb.fDR()`**, where it is token
**204**. In **`eQd.fGC()`**, the token-204 branch instantiates **`eLZ`**:

```text
eQb.fDR():
  0: sipush        204
  7: ldc_w         // String HasAnotherSameEquipment
 10: invokevirtual // Method match:(Ljava/lang/String;)V

eQd.fGC(), token-204 branch:
 18097: sipush        204
 18103: invokevirtual // Method match:(Lorg/antlr/runtime/IntStream;ILorg/antlr/runtime/BitSet;)Ljava/lang/Object;
 18115: invokevirtual // Method fGz:()Ljava/util/ArrayList;
 18132: new           // class eLZ
 18137: invokespecial // Method eLZ."<init>":(Ljava/util/ArrayList;)V
```

## What eLZ compares

**`eLZ.a(Object,Object,Object,Object)`** reads the candidate `fnR`:

```text
 49: aload         6
 51: invokevirtual // Method fnR.awe:()I
 54: istore        8
 56: aload         6
 58: invokevirtual // Method fnR.LV:()J
 61: lstore        9
 63: aload         7
 65: iload         8
 67: lload         9
 69: aload         5
 71: invokestatic  // Method a:(LeGj;IJLfpj;)Z
```

Its `a(eGj,int,long,fpj)` checks either the equipment set or the equipped inventory:

- `b(eGj,int,long,fpj)` resolves the set's stored instance ids through `fpd.C(Long)`, drops nulls,
  filters out the candidate instance, then uses `anyMatch` on equal definition ids.
- `a(eGj,int,long)` calls `fnZ.pd(int)` (inherited from `Rk`) to select entries of that definition id,
  then uses `anyMatch` on an instance id different from the candidate's.

The actual comparison lambdas are **`eLZ.a(int,fnR)`** (definition equality) and
**`eLZ.a(long,fnR)` / `eLZ.b(long,fnR)`** (instance inequality):

```text
private static boolean a(int, fnR):
 0: aload_1
 1: invokevirtual // Method fnR.awe:()I
 4: iload_0
 5: if_icmpne     12
 8: iconst_1
 9: goto          13
12: iconst_0
13: ireturn

private static boolean a(long, fnR):
 0: aload_2
 1: invokevirtual // Method fnR.LV:()J
 4: lload_0
 5: lcmp
 6: ifeq          13
 9: iconst_1
10: goto          14
13: iconst_0
14: ireturn
```

`javap -v eLZ` confirms the set stream's invokedynamic bootstrap **3** points to
`eLZ.a(int,fnR)`, bootstrap **2** to `eLZ.b(long,fnR)`, and the equipped-inventory bootstrap **4**
to `eLZ.a(long,fnR)`. `Rk.pd(int)` directly compares `Rq.awe()` to the supplied id.

## Establishing that awe is the Item table id

**`fnR.awe()`** delegates to the item reference **`fpC.d()`**; **`fnR.LV()`** returns the separate
long instance field `lnf`:

```text
fnR.awe():
 0: aload_0
 1: getfield      // Field jZp:LfpC;
 4: invokevirtual // Method fpC.d:()I
 7: ireturn

fnR.LV():
 0: aload_0
 1: getfield      // Field lnf:J
 4: lreturn

fpC.d():
 0: aload_0
 1: getfield      // Field p:I
 4: ireturn
```

The Item loader **`bGT.a(bJq,aLO,Map)`** copies the binary-data record id into that reference:

```text
 0: aload_0
 1: aload_1
 2: invokevirtual // Method aLO.d:()I
 5: invokevirtual // Method bJq.Ws:(I)LfpD;
```

`fpD.Ws(int)` invokes `fpC.lV(int)`, which writes `fpC.p`. `aLO.d()` reads its own first integer
field `p`; `aLO.bGI()` returns `eED.oWU.d()`. The enum initializer labels `oWU` **ITEM** with table
id **35**. This is the same structural Item table already decoded by `ItemCriteria`.

## Issé Sceau explains the explicit cross-rarity bans

The committed item criteria contain three distinct ids with French name `Issé Sceau`:

| Id | Rarity | Explicitly forbids |
| --- | --- | --- |
| 19698 | Legendary | 22226, 22227 |
| 22226 | Mythic | 22227, 19698 |
| 22227 | Rare | 22226, 19698 |

Each also has `not HasAnotherSameEquipment()`. Those explicit bans cover the other definition ids,
which the generic same-definition criterion alone does not reject.

## Reproduce

Read-only inspection; no game execution or engine changes:

```sh
javap -c -p -classpath /Applications/Ankama/Wakfu/lib/wakfu-client.jar eQb eQd eLZ fnR fpC fpD Rk bGT aLO eED
javap -v -p -classpath /Applications/Ankama/Wakfu/lib/wakfu-client.jar eLZ
```

Obfuscated class names here are evidence for this exact jar, not stable extraction identifiers.
Validation for the batch: `ktlintFormat`, `:gui-compose:test`, targeted `:autobuilder:test`, and
`:bdata-extractor:test` through the requested shared Gradle gate. No research-only changeset is needed.
