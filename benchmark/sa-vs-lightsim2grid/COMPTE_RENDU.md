# Compte rendu : accélération de l'analyse de sécurité AC d'OpenLoadFlow

Branche : `claude/clever-edison-qs6x0z`. Les commits sont listés à la fin.

L'objectif était d'amener l'analyse de sécurité N-1 d'OpenLoadFlow (OLF) au niveau de
lightsim2grid (LS2G). Le diagnostic de départ est dans [RESUME.md](RESUME.md) et
[README.md](README.md).

## Dernier état : comparaison mono-thread, avec une bibliothèque native améliorée

Le cas testé est `case6515rte` en N-1 sur les 7422 lignes, dont 2162 créent un îlot, en NR pur
(slack unique, sans limites réactives) et sur **1 thread**.

Toutes les variantes ont été exécutées **deux fois, en alternance**, sur la même machine. Le bruit
est de ±15 % d'un créneau à l'autre ; le tableau donne le meilleur des deux tours, puis le premier.

| Variante | Temps | Par contingence | Résultats |
|---|---|---|---|
| OLF d'origine (`16838da`) + powsybl-math-native 1.5.0 officiel | 317,3 s (318,1) | 42,8 ms | référence |
| OLF branche + powsybl-math-native 1.5.0 officiel | 78,4 s (91,4) | 10,6 ms | identiques ¹ |
| **OLF branche + powsybl-math-native amélioré** (`-Drgrowth=-1e-12`) | **67,8 s** (78,5) | **9,1 ms** | identiques ¹ |
| lightsim2grid 1.3.0, sans violations | 43,4 s (48,7) | 5,8 ms | |
| lightsim2grid 1.3.0, avec violations (2,8 M, non filtrées) | 46,6 s (47,3) | 6,3 ms | |

¹ Mêmes statuts (7420 convergées, 2 en limite d'itérations) et mêmes 1 021 140 violations.

- Le gain total est **×4,7** par rapport à l'origine.
- **L'écart avec lightsim2grid passe de ×6,8 à ×1,45** à travail comparable (violations comprises).
- **La parité n'est pas atteinte** : l'écart restant est entièrement côté Java (voir plus bas).

### Bibliothèque native améliorée (powsybl-math-native)

Le patch est dans `math-native/powsybl-math-native-v1.5.0-perf.patch`, avec un script de
construction `math-native/build.sh`. Le script est reproductible : il produit une bibliothèque
binairement identique à celle qui a été mesurée. Il suffit de placer le répertoire produit en tête
du classpath.

1. **Accès sans copie aux tableaux Java.** Les tableaux de la matrice (`ap`, `ai`, `ax`) passent par
   `GetPrimitiveArrayCritical`, libérés avec `JNI_ABORT` puisqu'ils sont en lecture seule, et le
   second membre de `solve` passe aussi en accès critique. Auparavant, `Get/Release*ArrayElements`
   en mode 0 copiait chaque tableau à l'entrée puis à la sortie. Ce changement ne modifie pas le
   comportement.
2. **Contrôle de stabilité bon marché, en option.** Un seuil `rgrowthThreshold` **négatif**, jusqu'ici
   sans signification, active `klu_refactor` suivi d'un contrôle par `klu_rcond`, en O(n) sur la
   diagonale de U, au lieu de `klu_rgrowth`, en O(nnz). Le seuil `|seuil|` est le rcond minimal ;
   si la refactorisation échoue ou si ce minimum n'est pas atteint, on repasse par `klu_factor`. Les
   seuils positifs ou nuls gardent exactement le comportement actuel.

   Côté OLF, le mode s'active en fournissant `new SparseMatrixFactory(-1e-12)`, sans modification de
   l'API Java de powsybl-math. Je ne l'ai **pas activé par défaut** : avec la bibliothèque officielle,
   un seuil négatif provoquerait un `klu_factor` à chaque mise à jour.
3. **Compilateur.** La bibliothèque doit être compilée avec **GCC 14**, comme l'officielle. Avec
   GCC 13, `klu_rgrowth` est 2,5 fois plus lent et `klu_refactor` 10 % plus lent, sur des sources
   identiques.

**Validation.**

- Les **1488 tests OLF passent avec la bibliothèque améliorée**, en mode par défaut (seuil positif).
- Ils passent aussi lorsque **toutes** les `SparseMatrixFactory` d'OLF sont forcées en mode `rcond`.
- Sur la jacobienne de ce cas, une refactorisation coûte 1,58 ms dans OLF en mode `rcond`, contre
  2,06 ms avec la bibliothèque officielle. En banc C, `klu_refactor` seul coûte 1,4 ms.

### D'où vient l'écart restant (≈ 9,1 contre ≈ 6,3 ms par contingence)

- **KLU.** Il coûte maintenant ≈ 2,4 ms par contingence dans OLF (1,31 refactorisation, plus les
  résolutions). Lightsim2grid en fait ≈ 2 par contingence, pour ≈ 2,8 ms : **KLU n'est plus en
  cause**.
- **Java.** Il représente ≈ 6,7 ms par contingence, contre ≈ 3,5 ms pour l'équivalent C++ de
  lightsim2grid :
  - restauration de l'état réseau ≈ 1,5 ms ;
  - remplissage de la jacobienne ≈ 1,2 ms ;
  - initialisation de l'état et des transits ≈ 1,0 ms ;
  - mismatch ≈ 1,3 ms ;
  - mise à jour des transits ≈ 0,8 ms ;
  - violations ≈ 0,9 ms.

  En place, ces opérations coûtent 2 à 5 fois leur coût en micro-benchmark (défauts de cache sur
  des structures objet volumineuses). Combler cet écart demanderait de passer à des structures de
  données plates (tableaux) pour l'état réseau et sa restauration, et à un calcul matriciel des
  transits sur Ybus : c'est une refonte, pas une optimisation locale.

## Résultats de l'étape précédente (bibliothèque native officielle)

Le cas testé est `case6515rte` en N-1 sur les 7422 lignes, dont 2162 créent un îlot, sur 4 vCPU.
OLF d'origine (commit `16838da`) et OLF branche ont été **exécutés en alternance (A/B)**, sur la
même machine et dans le même créneau, à partir de classes figées. LS2G 1.3.0 a été lancé juste
après. Le bruit de la machine est de l'ordre de ±10 à 15 %.

| Configuration | OLF d'origine | **OLF branche** | Gain | LS2G | Écart OLF / LS2G : avant → après |
|---|---|---|---|---|---|
| NR pur (slack unique, sans limites réactives), 1 thread | 304,6 s | **70,7 s** | **×4,3** | 48 à 54 s ¹ | ×6 → **×1,3 à 1,5** |
| NR pur, 4 threads | 129,4 s | **36,8 s** | **×3,5** | 15,7 s | ×8 → **×2,3** |
| Paramètres par défaut OLF (boucles externes), 1 thread | 493,5 s | **306,8 s** | ×1,6 | 259,4 s ² | ×1,9 → **×1,2** |

¹ 47,9 s avec le calcul des violations activé dans LS2G, qui en produit 2,8 M non filtrées.
² LS2G `NROuter_KLU`, avec ses boucles externes qui reproduisent celles d'OLF.

- **Résultats identiques** avant et après : mêmes statuts de convergence, et même nombre de
  violations (1 021 140 en NR pur, 1 062 061 en configuration par défaut).
- **Tests OLF : 1488, 0 échec**. L'origine en comptait 1487 ; un test de non-régression a été
  ajouté.

**La parité n'est pas atteinte.** En mono-thread, OLF est maintenant à 1,3–1,5 fois LS2G, contre
6 fois au départ. Le reste de l'écart est expliqué plus bas. Une partie ne dépend pas de ce dépôt
(bibliothèque native KLU de powsybl).

## Où va le temps maintenant

Mesures chronométrées en NR pur sur 1 thread, en moyenne par contingence :

| Poste | Avant | Après | LS2G |
|---|---|---|---|
| Factorisations complètes KLU (`klu_analyze` + `klu_factor`) | 7424 | **2** | 1 |
| KLU (`klu_refactor` + `rgrowth` + résolution, natif) | ≈ 14 ms | **≈ 3,2 ms** | ≈ 3 ms |
| Reconstructions de structure (index, jacobienne Java, vecteurs) | 3380 | **4** | 0 |
| Remplissage de la jacobienne, mismatch, mise à jour des transits | ≈ 12 ms | ≈ 3,2 ms | (inclus dans le C++) |
| Détection des violations | ≈ 3,6 ms | ≈ 0,9 ms | ≈ 0,5 ms (non filtrées) |
| Restauration de l'état réseau | ≈ 1,9 ms | ≈ 1,6 ms | 0 |
| **Total par contingence** | **≈ 41 ms** | **≈ 9,5 ms** | **≈ 6,5 à 7 ms** |

## Modifications réalisées

### 1. Solveur linéaire (KLU)

- **Numérotation alignée des variables sur les équations** (`EquationSystem`,
  `EquationSystemIndex`, `AcEquationSystemCreator.getDiagonalVariableType`).
  - En mode vectorisé, la diagonale de la jacobienne était vide, ce qui donnait **+44 %** de
    remplissage LU.
  - Chaque équation est maintenant associée à sa variable diagonale : P↔φ, Q↔V, V↔V, etc.
  - Le vecteur d'état est permuté lorsqu'une renumérotation intervient en cours de calcul.
- **Matrice CSC triée transmise à KLU**, dont l'ordonnancement dépend de l'ordre des indices.
- **`klu_refactor` au lieu de `klu_factor`** après le déclenchement d'une ligne, avec le contrôle
  `rgrowth` et un repli sur `klu_factor` si nécessaire.
- **Structure LU conservée quand la jacobienne rétrécit** (`JacobianLuStructure`) : les valeurs
  sont projetées sur la structure précédente, avec l'identité pour les équations retirées.

### 2. Contingences créant un îlot : mode fantôme de l'index

Ce mode n'est actif qu'en analyse de sécurité AC, avec le solveur Newton-Raphson, en équilibré.

- **Équations et variables fantômes.** Une équation désactivée garde sa colonne (fantôme). Une
  variable qui n'est plus référencée garde sa ligne (orpheline).
- **Contenu des colonnes fantômes.** Elles valent l'identité dans la jacobienne et zéro dans les
  vecteurs de mismatch et de cibles. Le critère d'arrêt du Newton ne les compte pas.
- **Décision sur l'effet net.** Les décisions structurelles sont prises à la mise à jour de l'index.
  Les activations transitoires, faites quand un bus est réactivé, s'annulent. S'il reste de vraies
  nouvelles équations, l'index est reconstruit comme avant.
- **Effet.** Sur les 2162 contingences avec îlot, le temps passe de 59 s à 22 s. Une contingence
  avec îlot ne coûte plus davantage qu'une contingence sans îlot.

### 3. Travail Java par itération et par contingence

| Poste | Modification | Avant | Après |
|---|---|---|---|
| Remplissage de la jacobienne | « Scatter-add » direct à partir d'indices mémorisés ; plus de parcours de `TreeMap` | 1,74 ms | ≈ 0,5 ms |
| Évaluation du mismatch | Tables aplaties (terme, élément, colonne) | 0,50 ms | 0,09 ms |
| Mise à jour d'état (transits) | Un seul `sinAndCos` par branche (θ₁ = ξ + d, θ₂ = ξ − d) ; courants calculés paresseusement | 0,84 ms | 0,55 ms |
| Vecteur des cibles | Mise à jour partielle (bus modifiés et colonnes fantômes basculées) au lieu d'un recalcul complet | 1,7 ms | 0,03 ms |
| Détection des violations | Côtés de branche surveillés précompilés avec le seuil minimal ; chemin détaillé seulement en cas de dépassement | ≈ 3,6 ms | ≈ 0,9 ms |

Divers :

- **Restauration de l'état :**
  - les générateurs sont restaurés depuis des tableaux ;
  - le statut des charges est versionné : il n'est réécrit que s'il a changé.
- **Contrôles moins coûteux :**
  - `MonitoringVoltageOuterLoop.isNeeded` fait d'abord un pré-test bon marché ;
  - le contrôle d'état non réaliste ne cherche le bus que si la tension est hors plage.

### 4. Bugs corrigés en chemin

- **`PiModelArray.shiftOneTapPositionToChangeA1`.** Après avoir avancé d'une prise, la méthode
  pouvait revenir à la prise de départ dès que α n'était pas exactement 0.
- **Restauration du statut des charges d'origine** (`BusDcState`). La table sauvegardée était
  partagée avec la charge : après une contingence qui perd la charge, celle-ci restait
  déconnectée pour toutes les contingences suivantes, et les actions sur cette charge étaient
  ignorées. Un test de non-régression a été ajouté (`NetworkStateTest`) ; il échoue sur l'ancien
  code.

### Tests modifiés

Deux attendus figés ont été mis à jour :

- `SecondaryVoltageControlTest` : écart au 6ᵉ chiffre dans un rapport, dans la tolérance de
  convergence.
- `EquationArrayTest.testSparseReproducibility` : nouvelle numérotation, dont la reproductibilité a
  été vérifiée.

## Ce qui reste, et pourquoi

1. **KLU via powsybl-math-native : ≈ 3,2 ms par contingence, contre ≈ 3 ms pour LS2G avec plus
   d'itérations.** Chaque `update` de powsybl-math-native :
   - fait systématiquement un `klu_rgrowth`, soit ≈ 0,35 ms ;
   - copie les tableaux de la matrice à l'entrée et à la sortie de JNI
     (`Get/Release*ArrayElements` en mode 0), soit ≈ 0,3 ms.

   Une refactorisation coûte ainsi 2,15 ms, contre 1,35 ms dans LS2G. Corriger cela demande une
   évolution de **powsybl-math-native**, hors de ce dépôt : `rgrowth` optionnel,
   `GetPrimitiveArrayCritical` et `JNI_ABORT` pour les tableaux en lecture seule.
2. **Restauration de l'état (≈ 1,6 ms).** OLF restaure tous les bus et branches à chaque
   contingence. Une restauration incrémentale demanderait que toute modification d'état émette un
   événement, ce qui n'est pas le cas aujourd'hui (prises, gel de Q, participation…). Je ne l'ai
   pas faite, à cause du risque de restauration incomplète.
3. **Calculs Java contre C++.** Les transits et dérivées sont calculés par branche, en Java
   (≈ 2 ms par contingence), là où LS2G fait des produits creux Ybus en C++.
4. **Montée en charge multi-thread.** Avec 4 threads, OLF ralentit d'un facteur ≈ 2 par thread,
   contre ≈ 1,2 pour LS2G. Le GC est négligeable (0,65 s) ; la cause est la contention mémoire,
   avec des structures objet bien plus volumineuses.
5. **Configuration par défaut.** Les basculements PV↔PQ de la boucle des limites réactives
   changent encore la structure : l'équation de consigne V est remplacée par l'équation Q. Une
   extension du mode fantôme, avec « échange » d'équation sur la même variable diagonale,
   permettrait de l'éviter.

## Essai : sauvegarde d'état vectorisée

`NetworkState` stocke maintenant l'état des bus (avec générateurs, charges et shunts) et des
branches dans des tableaux plats indexés par numéro d'élément (`BusesVectorState`,
`BranchesVectorState`), au lieu d'un objet `BusState`/`BranchState` par élément. Les setters sont
appelés exactement dans le même ordre qu'avant, donc le comportement est identique (1488 tests OK,
mêmes 1 021 140 violations sur le benchmark).

| Mesure | Avant | Vectorisé |
|---|---|---|
| Restauration des branches (micro-benchmark) | 306 µs | 157 µs (−49 %) |
| Restauration des bus (micro-benchmark) | 589 µs | 550 µs (−7 %) |
| Benchmark complet, A/B alterné ×3 (bibliothèque native améliorée) | 78,95 / 80,62 / 81,17 s | 79,42 / 80,46 / 79,41 s |

Gain sur le benchmark complet : ≈ 0,5 s en moyenne (80,2 → 79,8 s), dans le bruit de la machine.
Pourquoi c'est si peu :

- le coût de la restauration des bus vient des **setters**, pas de la lecture de l'état : chaque
  `setDisabled`, `setV`, `setGeneratorVoltageControlEnabledAndRecomputeTargetQ`… notifie des
  listeners et met à jour des champs dérivés. Les tableaux plats ne changent rien à ce coût ;
- on ne peut pas sauter les setters dont la valeur n'a pas changé sans risque : par exemple
  `LfBus.setDisabled` se propage aux shunts et aux HVDC, qui ne sont pas sauvegardés à part ;
- la restauration des branches est divisée par deux, mais elle ne pèse que ≈ 0,3 ms sur ≈ 10 ms
  par contingence.

Pour aller plus loin, il faudrait des setters « silencieux » (écriture directe des champs, puis
une seule notification groupée vers le système d'équations), ou une restauration incrémentale des
seuls éléments touchés par la contingence. Les deux demandent de revoir le modèle `LfNetwork` et
ses listeners.

## Reproduire

Bibliothèque native améliorée :

```bash
benchmark/sa-vs-lightsim2grid/math-native/build.sh /tmp/math-native
```

Puis lancer le benchmark avec `-cp /tmp/math-native:<classpath> -Drgrowth=-1e-12`.

Le protocole et les scripts sont décrits dans le [README](README.md).

```bash
java -cp <classes>:<deps>:<bench> bench.OlfN1Bench case6515rte.xiidm <cfg> 1
```

Configurations :

| Configuration | Signification |
|---|---|
| `nr` | NR pur, 1 thread |
| `nr_t4` | NR pur, 4 threads |
| `default` | paramètres par défaut |
| suffixe `_count` | compte les appels KLU |
| `-Dexclude=…` ou `-Donly=…` | filtre les contingences |

## Commits

| Commit | Contenu |
|---|---|
| `287a77a` | Numérotation alignée, CSC triée, `klu_refactor`, correction de `PiModelArray` |
| `1947e8a` | Structure LU conservée quand la jacobienne rétrécit |
| `a99b6d8` | Courants paresseux, `der` sans `TreeMap` par ligne, états des générateurs en tableaux |
| `529723b` | Remplissage rapide de la jacobienne (scatter-add) |
| `bf33b1c` | Reconstruction de structure moins chère |
| `df7e5e6` | Détection des violations précompilée |
| `3ac7f6e` | Correction de la restauration des charges, mismatch aplati, un seul `sinAndCos` par branche |
| `3234db3` | Mode fantôme (îlots sans changement de structure) |
| `b168839` | Suppression des `TreeMap` des chemins chauds, restauration des charges versionnée |
| `e9d03e8` | Mise à jour partielle du vecteur des cibles |
| `c14ffe2` | `isNeeded` et contrôle d'état réaliste moins chers |
| `839ae4a` | Restauration moins chère (bus slack exclus, boucle unique sur les générateurs) |
