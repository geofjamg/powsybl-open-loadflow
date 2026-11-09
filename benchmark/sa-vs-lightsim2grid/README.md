# Analyse de sécurité N-1 : OpenLoadFlow vs lightsim2grid (case6515rte)

Pourquoi l'analyse de sécurité AC d'OpenLoadFlow (OLF) est-elle plus lente que celle de
lightsim2grid (LS2G), alors que les deux utilisent KLU ?

## Protocole

- **Réseau** : MATPOWER `case6515rte`, converti en `.mat` (`python/m2mat.py`), puis importé par le
  convertisseur MATPOWER → IIDM de powsybl. Le réseau obtenu compte 6515 bus, 7422 lignes,
  1615 transformateurs et 1389 groupes.
- **Même réseau des deux côtés** : LS2G est construit depuis le *même* IIDM
  (`lightsim2grid.gridmodel.init_from_pypowsybl`).
- **Cas de base comparé** : avec un slack unique, sans limites réactives ni réglages, l'écart
  maximal de transit est de 0,10 MW sur les lignes (médiane 1e-4 MW) et de 0,12 MW sur les
  transformateurs.
- **Contingences** : N-1 sur les 7422 lignes. 2162 d'entre elles créent un îlot (ponts du graphe).
- **Versions** : OLF 2.4.0-SNAPSHOT (ce dépôt), powsybl-core 7.3.0, powsybl-math-native 1.5.0
  (SuiteSparse 7.3.1). LS2G 1.3.0 (KLU 2.3), pypowsybl 1.16.1.
- **Machine** : 4 vCPU, Java 21, Python 3.13. Les temps sont des temps murs de l'analyse complète.
- **Configurations** :
  - **NR pur** : slack unique, sans limites réactives, sans réglages de transformateurs ni de
    shunts. Pour OLF, l'initialisation est `DC_VALUES`, puis chaque contingence part de la
    solution N.
  - **Défaut** : paramètres par défaut d'OLF (slack distribué, limites réactives…). Côté LS2G,
    l'équivalent est `NROuter_KLU`, avec ses boucles externes qui reproduisent celles d'OLF.
- **Critère d'arrêt** :
  - LS2G : norme infinie du mismatch < 0,01 MVA, et 1e-6 MVA pour la variante `tight`.
  - OLF : critère par défaut ‖f‖₂ < 1e-4·√n p.u.
  - Le critère OLF est *plus lâche* : OLF fait 1,3 à 1,4 résolution par contingence, contre
    2,0 itérations pour LS2G. L'écart ne vient donc pas de là.

## Résultats

| Configuration | Contingences | OLF | LS2G | Ratio |
|---|---|---|---|---|
| NR pur, 1 thread, îlots calculés | 7422 | **≈ 260 s** (249–280 s) | **43,2 s** | ×6 |
| NR pur, 1 thread, LS2G ignorant les îlots | 7422 (5260 résolues côté LS2G) | ≈ 260 s | 32,7–38,2 s | ×7 |
| NR pur, 1 thread, contingences sans îlot | 5260 | 145,7 s | 32,7–38,2 s ¹ | ×4 |
| NR pur, 4 threads | 7422 | 119,7 s | 12,8 s | ×9 |
| Défaut (boucles externes) | 7422 | 469–476 s | 253,6 s ² | ×1,9 |
| LS2G, tolérance 1e-6 MVA | 7422 (5260 résolues) | – | 38,1 s | – |

¹ Ce temps inclut 3 à 14 s de prétraitement (analyse de connexité).
² LS2G avec ses boucles externes « OLF-like » : 34 ms par contingence et 11 refactorisations
par contingence.

Côté LS2G, une contingence du cas NR pur coûte ≈ 5,8 ms : modification de Ybus par
« coefficient -= valeur » en conservant le motif creux, environ 2 itérations
`fill_J` + `klu_refactor` + `solve`, et rien d'autre. Sur tout le batch, il n'y a qu'**une seule
factorisation complète** (`nb_factorize=1`, `nb_refactorize=14669`, `nb_refactorize_failed=0`).

## Décomposition du temps OLF (NR pur, 7422 contingences, ≈ 280 s instrumentées)

Les appels KLU ont été comptés et chronométrés par un décorateur de `MatrixFactory`
(`java/com/powsybl/math/matrix/CountingMatrixFactory.java`). La partie Java vient d'un profil JFR,
en échantillons Java uniquement, et chaque échantillon est attribué à une seule catégorie.

| Poste | Appels | Temps | Équivalent LS2G |
|---|---|---|---|
| `klu_analyze` + `klu_factor` (nouvelle LU après changement de structure) | 3380 | 57,2 s | 1 seule fois |
| `klu_factor` complet (`update(false)`) | 4044 | 34,2 s | 0 |
| `klu_refactor` + `klu_rgrowth` | 2276 | 8,9 s | 14669 refactors, ≈ 20 s au total |
| `klu_solve` | 9700 | 4,4 s | |
| **Sous-total KLU** | | **≈ 105 s** | **≈ 23 s** |
| Jacobienne Java : remplissage des valeurs (`updateDer`) | | ≈ 24 % du Java, ≈ 43 s | `fill_J` C++ inclus dans le solveur |
| Vecteur de mismatch (`EquationVector`) | | ≈ 19 %, ≈ 33 s | |
| Jacobienne Java : reconstruction de structure (`initDer`) | 3380 | ≈ 16 %, ≈ 29 s | 0 |
| Détection des violations (1 021 140 violations produites) | | ≈ 13 %, ≈ 22 s | désactivé par défaut |
| `NetworkState.restore` (tous les bus et branches à chaque contingence) | | ≈ 11 %, ≈ 19 s | inexistant (« -= » puis « += » sur Ybus) |
| Courants et transits de *toutes* les branches à chaque mise à jour d'état (`AcNetworkVector`, `hypot`) | | ≈ 8,5 %, ≈ 15 s | calcul paresseux, après le batch |
| Divers NR, boucles externes, contrôle d'état réaliste | | ≈ 7 % | |

## D'où viennent les écarts (par ordre d'impact)

### 1. Contingences qui créent un îlot : reconstruction complète de la jacobienne et de la LU (≈ 86 s)

Quand une contingence isole des bus, OLF désactive leurs équations et variables. L'événement
`JacobianMatrix` reçu est alors `STRUCTURE_INVALID` :

- OLF reconstruit la matrice creuse en Java (`initDer`) ;
- il crée une nouvelle `SparseLUDecomposition`, soit `klu_analyze` + `klu_factor` ;
- `NetworkState.restore()` réactive ensuite ces bus, ce qui provoque **une seconde
  reconstruction** à la contingence suivante.

Le comptage le confirme : 3380 reconstructions sur l'ensemble des contingences, et **2 seulement**
quand on exclut les 2162 contingences créant un îlot.

LS2G détecte les îlots une seule fois, par un parcours de graphe avant le batch. Il **masque** les
bus isolés en les remplaçant par des équations identité, *avec le même motif creux* : il n'y a
aucune nouvelle analyse symbolique.

### 2. Un `klu_factor` complet à la première itération de chaque contingence, au lieu d'un `klu_refactor` (≈ 34 s, puis ≈ 15–20 s)

Le déclenchement d'une ligne désactive des termes. `JacobianMatrix` passe alors en
`VALUES_AND_ZEROS_INVALID`, qui appelle `lu.update(false)`. Côté natif
(`powsybl-math-native/src/main/cpp/lu.cpp`), cela correspond à `klu_free_numeric` +
**`klu_factor`**, c'est-à-dire une factorisation numérique complète avec pivotage, qui coûte
≈ 8 ms ici. Pourtant, le motif creux est conservé (les zéros restent explicites), donc un
`klu_refactor` suffirait.

**Expérience** : `VALUES_AND_ZEROS_INVALID` remplacé par `updateValues(true)`, soit
`klu_refactor` + contrôle `rgrowth`, avec repli automatique sur `klu_factor`. Sur les 5260
contingences sans îlot, le temps passe de **145,7 s à 127,7 s (−12 %)**, avec des résultats
identiques (mêmes statuts, 704 730 violations dans les deux cas). Il reste toutefois
**1507 échecs de `klu_refactor`** (pivot nul, puis repli). C'est la conséquence du point 3.

### 3. Numérotation de la jacobienne en mode vectorisé : KLU environ deux fois plus cher sur *toutes* les opérations

En mode vectorisé (le mode par défaut, `AcLoadFlowParameters.vectorizedDefaultValue = true`) :

- les **colonnes** (équations) sont rangées ainsi : équations simples (consigne V, φ du slack),
  puis bloc `BUS_TARGET_P` de tous les bus, puis bloc `BUS_TARGET_Q` ;
- les **lignes** (variables) sont triées par (bus, type), donc entrelacées : φ₀, V₀, φ₁, V₁…

Résultat : la diagonale est **structurellement vide** (13 017 zéros sur 13 030) et la symétrie du
motif vaut 0,07 %. KLU doit faire un *maximum transversal* (BTF) pour trouver une diagonale non
nulle. L'appariement trouvé n'est pas l'appariement naturel P↔φ, Q↔V. AMD, qui travaille sur
A+Aᵀ, ordonne alors un motif asymétrique, ce qui produit **+44 % de remplissage**.

Même benchmark C (`native/klubench.c`), mêmes matrices, médiane de 200 appels :

| Matrice (lib powsybl-math-native) | Symétrie du motif | nnz(L+U) | analyze | factor | refactor | solve |
|---|---|---|---|---|---|---|
| OLF vectorisée (défaut) | 0,0007 | 211 114 | 9,0 ms | 7,6 ms | 2,6 ms | 0,45 ms |
| OLF vectorisée, lignes réalignées (appariement de poids max sur \|J\|) | 0,96 | 149 780 | 4,0 ms | 4,0 ms | 1,3 ms | 0,28 ms |
| OLF non vectorisée | 0,98 | 148 654 | 3,7 ms | 4,2 ms | 1,3 ms | 0,26 ms |
| LS2G | 0,999 | 146 644 | 4,2 ms | 5,4 ms | 1,4 ms | 0,23 ms |

**Les deux bibliothèques KLU sont équivalentes.** La lib de LS2G donne les mêmes temps sur les
mêmes matrices (par exemple 2,8 ms en `refactor` sur la jacobienne OLF vectorisée), et des
permutations aléatoires symétriques ne changent rien. L'écart est entièrement dû à la
**numérotation**.

Une jacobienne bien alignée rend aussi `klu_refactor` robuste. En mode non vectorisé, avec le
patch du point 2, il n'y a **0 échec** sur 7340 `klu_refactor`, et le coût KLU total des 5260
contingences tombe de 54,6 s à **19 s**, au niveau de LS2G.

Le mode non vectorisé n'est cependant pas une solution en soi. Le reste du code Java y est bien
plus lent : 373 s au lieu de ≈ 260 s pour 7422 contingences, 223,6 s au lieu de 145,7 s sans
îlot. Il faut donc **garder la vectorisation et aligner la numérotation**, par exemple en
numérotant les variables dans le même ordre que les colonnes, ou en entrelaçant P/Q par bus.

### 4. Travail Java par itération Newton plus lourd (≈ 90 s)

- **Remplissage de la jacobienne** (`updateDer`) : chaque appel réévalue les dérivées de chaque
  terme de branche, avec trigonométrie par branche. Il coûte 1,9 ms en micro-benchmark et
  ≈ 6 ms dans l'analyse de sécurité. Côté LS2G, `fill_J` utilise des produits creux Eigen sur Ybus.
- **Mismatch** (`EquationVector`) : ≈ 0,6 ms par évaluation en micro-benchmark, davantage dans
  l'analyse de sécurité.
- **À chaque mise à jour du vecteur d'état** : `AcNetworkVector.updateClosedBranches` recalcule
  P, Q et I de **toutes** les branches (`FastMath.hypot`), soit ≈ 0,8 ms à chaque itération,
  alors que ces valeurs ne servent qu'à la fin.

### 5. Fonctionnalités d'analyse de sécurité absentes du calcul LS2G (≈ 40 s)

- **Détection des violations** : `LimitViolationManager.detectViolations` parcourt toutes les
  branches et tous les bus, et crée ici plus d'un million d'objets `LimitViolation`. Les limites
  de tension MATPOWER sont très violées dès le cas N, puisque Vmin ≈ 0,56 p.u.
- **`NetworkState.save/restore`** : 5 ms et 1,5 ms par contingence, en parcourant *tous* les bus
  et branches. LS2G n'a rien à restaurer : il fait « -= » puis « += » sur 4 coefficients de Ybus.
- Objets de résultat, `ReportNode`, etc. : leur coût est faible.

### 6. Parallélisme

OLF gagne ×2,2 avec 4 threads (un `LfNetwork` par partition). LS2G gagne ×3,4 (un solveur et
une copie de Ybus par thread).

### 7. Configuration par défaut (boucles externes)

Le surcoût des boucles externes est réel des deux côtés. LS2G passe de 43 s à 254 s quand il
active ses boucles « OLF-like ». OLF passe de ≈ 260 s à ≈ 470 s : chaque basculement PV/PQ change
la structure (7550 reconstructions jacobienne + LU, soit 131 s de `klu_analyze` + `klu_factor`
à lui seul). Avec un traitement des limites réactives à motif constant, comme le fait LS2G, la
plus grande partie de ce coût disparaîtrait.

## Pistes d'amélioration pour OLF (gains estimés en NR pur, 1 thread, 7422 contingences)

1. **Garder le motif creux quand un bus est isolé ou bascule PV↔PQ.** Il suffit de remplacer
   l'équation par une identité, ou de garder la variable avec une équation triviale, plutôt que
   de retirer équations et variables. Cela supprime les ≈ 3400 couples `initDer` +
   `klu_analyze` + `klu_factor` : **≈ −80 s** en NR pur, encore plus en configuration par défaut.
2. **Numéroter variables et équations de façon alignée en mode vectorisé.** Toutes les opérations
   KLU sont ≈ ×2 moins chères, et `klu_refactor` ne retombe plus en échec : **≈ −40 s**.
3. **`klu_refactor` plutôt que `klu_factor` sur `VALUES_AND_ZEROS_INVALID`**, le contrôle `rgrowth`
   assurant le repli. Le gain mesuré est de −18 s sur 5260 contingences ; combiné au point 2,
   il n'y a plus aucun `klu_factor`.
4. **Calcul paresseux des courants et transits de branche** : seulement après convergence, pour
   la détection des violations. Restauration incrémentale de l'état, limitée aux éléments
   modifiés. Détection des violations restreinte aux éléments surveillés : **≈ −30 à −50 s**.

Ces gains sont estimés à partir des comptages et ne sont pas strictement additifs. Les points 1 à 3
ramèneraient néanmoins la partie KLU d'OLF au niveau de LS2G (≈ 20–25 s au lieu de ≈ 105 s). Ce
qui resterait ensuite viendrait du Java par itération (points 4 et 5).

## Reproduire

```bash
./run.sh /tmp/sa-bench
```

| Fichier | Rôle |
|---|---|
| `python/m2mat.py` | MATPOWER `.m` → `.mat`, lu par l'importeur powsybl |
| `python/ls_base.py` | Cas de base LS2G vs OLF, export de la jacobienne LS2G |
| `python/ls_n1.py` | Benchmark N-1 LS2G (`ContingencyAnalysisCPP`) |
| `python/islanding_lines.py` | Lignes créant un îlot (ponts) |
| `python/jacobian_structure.py` | Symétrie, diagonale, réalignement des lignes |
| `python/jfr_cat.py` | Ventilation d'un profil JFR |
| `java/bench/OlfN1Bench.java` | Benchmark N-1 OLF. Suffixes de configuration : `_count` pour compter les appels KLU, `_novec` pour le mode non vectorisé, `_t4` pour 4 threads. `-Dexclude=fichier` exclut des contingences. |
| `java/bench/OpCost.java` | Coût unitaire de chaque brique NR sur la vraie jacobienne |
| `java/bench/ExportJ.java` | Export CSC de la jacobienne OLF (`-Dnovec=true` pour le mode non vectorisé) |
| `native/klubench.c` | Chronométrage KLU sur une matrice CSC, avec la lib de son choix (`dlopen`) |
