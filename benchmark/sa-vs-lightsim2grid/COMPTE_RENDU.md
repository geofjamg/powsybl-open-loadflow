# Compte rendu : accélération de l'analyse de sécurité AC d'OpenLoadFlow

Branche : `claude/clever-edison-qs6x0z`. Les commits sont listés à la fin.

L'objectif était d'amener l'analyse de sécurité N-1 d'OpenLoadFlow (OLF) au niveau de
lightsim2grid (LS2G). Le diagnostic de départ est dans [RESUME.md](RESUME.md) et
[README.md](README.md).

## Dernier état : comparaison mono-thread, avec une bibliothèque native améliorée

Le cas testé est `case6515rte` en N-1 sur les 7422 lignes, dont 2162 créent un îlot, en NR pur
(slack unique, sans limites réactives) et sur **1 thread**.

Toutes les variantes ont été exécutées **en alternance**, sur la même machine. Le bruit est de
±15 % d'un créneau à l'autre ; le tableau donne le meilleur des tours, puis le premier entre
parenthèses.

| Variante | Temps | Par contingence | Résultats |
|---|---|---|---|
| OLF d'origine (`16838da`) + powsybl-math-native 1.5.0 officiel | 317,3 s (318,1) | 42,8 ms | référence |
| OLF branche + powsybl-math-native 1.5.0 officiel | 78,4 s (91,4) | 10,6 ms | identiques ¹ |
| OLF branche + powsybl-math-native amélioré (`-Drgrowth=-1e-12`) | 67,8 s (78,5) | 9,1 ms | identiques ¹ |
| idem + restauration d'état incrémentale (`42d48c3`) ² | 64,5 s (69,1) | 8,7 ms | identiques ¹ |
| idem + optimisations Java sans toucher à `EquationArray` (`6cf6827`) ³ | 49,6 s (50,1) | 6,7 ms | identiques ¹ |
| **idem + pistes 1 à 5** (`7258e33`) ⁴ | **47,0 s** (47,8) | **6,3 ms** | identiques ¹ |
| lightsim2grid 1.3.0, sans violations | 42,8 à 46,8 s selon le créneau | 5,8 à 6,3 ms | |
| lightsim2grid 1.3.0, avec violations (2,8 M, non filtrées) | 43,4 à 49,3 s selon le créneau | 5,8 à 6,6 ms | |

¹ Mêmes statuts (7420 convergées, 2 en limite d'itérations) et mêmes 1 021 140 violations.
² Mesuré dans un créneau ultérieur, en A/B sur 3 tours contre la version précédente (75,2 → 66,6 s
en moyenne, soit −11 %) ; voir la section « Restauration d'état incrémentale ».
³ A/B sur 3 tours, en alternance avec la version `f663a8c` (restauration incrémentale) :
63,9 / 68,2 / 69,4 s contre 50,1 / 49,6 / 52,0 s, soit **−25 %** en moyenne (67,2 → 50,6 s).
lightsim2grid a été relancé juste après, dans le même créneau. Voir la section « Dernière série :
optimisations Java sans toucher à `EquationArray` ».
⁴ A/B sur 3 tours, en alternance avec `6cf6827`, dans un créneau plus lent : 56,0 / 61,9 / 55,0 s contre
47,8 / 49,4 / 47,0 s, soit **−17 %** en moyenne (57,6 → 48,0 s). lightsim2grid, relancé juste après dans le
même créneau : 46,8 s sans violations, 49,3 s avec. Voir la section « Pistes 1 à 5 ».

- Le gain total est **×6,7** par rapport à l'origine (317,3 → 47,0 s ; mesures de créneaux différents).
- **Dans le même créneau, OLF est au niveau de lightsim2grid** : 48,0 s en moyenne (47,0 à 49,4 s),
  contre 46,8 s sans violations et 49,3 s avec violations pour lightsim2grid. L'écart restant est dans
  le bruit de la machine (±10 %).
- En configuration par défaut (boucles externes), A/B sur 2 tours dans un créneau lent : 345,8 / 337,3 s
  → 325,6 / 324,6 s (−5 %), mêmes 1 062 061 violations. Dans un créneau précédent, OLF était à 272,7 s contre 259,4 s pour
  lightsim2grid `NROuter_KLU`.

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

### D'où vient l'écart restant (≈ 6,7 contre ≈ 5,8 ms par contingence)

- **KLU (natif)** : ≈ 2,1 ms par contingence (1,3 refactorisation en moyenne), autant que
  lightsim2grid. **KLU n'est plus en cause.**
- **Java** : le reste, ≈ 4,6 ms par contingence. Mesures chronométrées en place (avant les deux
  derniers commits) :

| Poste Java | Par appel | Appels par contingence |
|---|---|---|
| Transits et dérivées par branche (`AcNetworkVector`) | ≈ 620 µs | 2,3 |
| Remplissage des valeurs de la jacobienne | ≈ 650 µs | 1,3 |
| Évaluation du mismatch | ≈ 200 µs | 2,3 |
| Initialisation de l'état (hors transits) | ≈ 420 µs | 1 |
| Recopie de l'état dans le réseau | ≈ 390 µs | 1 |

Les deux derniers commits ont ensuite réduit l'initialisation, la recopie (tensions stockées dans
des tableaux) et le remplissage de la jacobienne (accès direct à l'activité des termes). Le
calcul des transits est limité par la mémoire : il lit une vingtaine de tableaux par branche, que
la factorisation KLU a chassés du cache entre deux appels.

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

## Répartition du temps à l'étape précédente

Ce tableau date de l'étape précédente (bibliothèque native officielle, restauration complète).
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
  - l'état est stocké dans des tableaux plats (voir « Essai : sauvegarde d'état vectorisée ») ;
  - le statut des charges est versionné : il n'est réécrit que s'il a changé ;
  - la restauration est incrémentale (voir « Restauration d'état incrémentale »).
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

## Ce qui reste, et comment aller plus loin

**Déjà traité depuis la première version de ce compte rendu :**

- **Surcoût de powsybl-math-native** (`klu_rgrowth` systématique, copies JNI) : corrigé par le
  patch de `math-native/`. Il reste à le faire intégrer dans powsybl-math-native, puis à activer le
  mode `rcond` par défaut dans OLF.
- **Restauration de l'état réseau** : elle est maintenant incrémentale (≈ 0,8 % du temps Java,
  contre ≈ 15 %).

- **Termes « objet » inactifs, initialisation de l'état, violations, remplissage de la jacobienne** :
  voir la section « Dernière série ».

**Pistes restantes, en gardant `EquationArray` :**

1. **Disposition mémoire des données de branche.** Le calcul des transits lit une vingtaine de
   tableaux séparés par branche (paramètres, numéros de lignes, états). Regrouper les paramètres
   constants d'une branche dans un seul tableau entrelacé réduirait le nombre de flux mémoire. C'est
   interne à `AcBranchVector`, mais touche les évaluateurs.
2. **Violations lues dans les tableaux.** La détection relit encore P et Q de chaque branche
   surveillée via les objets (`computeApparentPower1`). Un accès direct aux tableaux serait plus
   rapide, mais demande une interface générique pour ne pas lier `LimitViolationManager` au mode
   vectorisé.
3. **Variables à trouver en tableaux plats.** L'initialisation de l'état et la recopie parcourent
   encore les objets `Variable` (≈ 13 000) ; des tableaux (type, élément, ligne) mis en cache par
   l'index supprimeraient ces accès.
4. **Moins de travail par itération.** Calculer les normes pendant l'évaluation du mismatch, et ne
   faire le contrôle d'état non réaliste qu'en fin de calcul.

**Hors de ce périmètre :**

- **Calculs en natif.** Faire ces calculs en C++ via JNI, comme lightsim2grid, irait plus loin,
  mais c'est un autre projet.
- **Montée en charge multi-thread.** Avec 4 threads, OLF ralentit d'un facteur ≈ 2 par thread,
  contre ≈ 1,2 pour LS2G. Le GC est négligeable (0,65 s) ; la cause est la contention mémoire, avec
  des structures objet bien plus volumineuses. À reprendre après les pistes ci-dessus.
- **Configuration par défaut.** Les basculements PV↔PQ de la boucle des limites réactives changent
  encore la structure : l'équation de consigne V est remplacée par l'équation Q. Une extension du
  mode fantôme, avec « échange » d'équation sur la même variable diagonale, permettrait de
  l'éviter.

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

## Restauration d'état incrémentale (setters qui marquent les éléments modifiés)

Le profil JFR de la restauration vectorisée montrait que les **notifications ne coûtent presque
rien** (≈ 3 % de la restauration) : les setters ne notifient déjà que si la valeur change. Des
setters « silencieux » avec notification groupée n'auraient donc rien apporté. Le coût réel est le
**parcours de tous les objets** (bus, générateurs, charges, shunts, branches, modèles en pi) : des
défauts de cache, pour restaurer des éléments qui, pour la plupart, n'ont pas changé.

L'idée a donc été retournée : chaque setter d'un attribut sauvegardé **marque l'élément modifié**
dans un `StateChangeTracker` du `LfNetwork` (un booléen et une liste d'indices, coût négligeable).
La restauration ne visite que les éléments marqués :

- bus marqués : restauration complète (générateurs, charges, shunts compris) ; modules et angles
  de tension : restaurés pour tous les bus, puisque chaque calcul les modifie tous ;
- branches marquées, plus les branches à prises (`PiModelArray`, modifiables par trop de chemins
  pour être suivies) ;
- un seul état peut s'appuyer sur le suivi : le dernier sauvegardé ou restauré complètement. Les
  autres font une restauration complète, puis reprennent le suivi. Ajouter ou retirer un bus ou une
  branche invalide le suivi.

Setters instrumentés : `setDisabled` (bus, branches, shunts, générateurs), cibles P/Q des
générateurs et charges, `initialTargetP`, participation, type de contrôle, statut des charges
d'origine, B/G des shunts, activation des contrôles de tension et de puissance réactive, gel de Q,
type de limite Q, `connectedSide1/2`, `a1`/`r1` des `SimplePiModel`.

**Vérification.** Avec `-Dolf.networkState.checkIncrementalRestore=true`, chaque restauration
incrémentale est comparée à une sauvegarde complète de l'état restauré, et une exception est levée
au premier écart. Aucun écart n'a été détecté :

- sur les 1488 tests ;
- sur les 7422 contingences en NR pur (1 021 140 violations) ;
- sur les 7422 contingences en configuration par défaut (boucles externes actives,
  1 062 061 violations, identique à avant).

La restauration complète reste disponible avec `-Dolf.networkState.noIncrementalRestore=true`.

| A/B alterné, NR pur, 1 thread | Passe 1 | Passe 2 | Passe 3 | Moyenne |
|---|---|---|---|---|
| Restauration vectorisée complète (`2d183a3`) | 74,8 s | 77,1 s | 73,5 s | 75,2 s |
| Restauration incrémentale (`42d48c3`) | 69,1 s | 66,2 s | 64,5 s | 66,6 s |

Gain : **−8,6 s, soit −11 %**, à résultats identiques. C'est plus que la part mesurée de la
restauration elle-même (≈ 1,2 ms par contingence) : en ne touchant plus tous les objets, on évite
aussi de chasser du cache les données du calcul qui suit.

## Dernière série : optimisations Java sans toucher à `EquationArray`

Pour garder le caractère générique du système d'équations, toutes les optimisations restent
internes à `EquationArray`, au réseau ou à l'index. Elles ont été guidées par des mesures en
place : JFR avec `-XX:+DebugNonSafepoints` (attribution exacte des lignes) et chronométrage des
étapes, car le profil JFR par défaut attribuait à tort le coût aux termes shunt.

| Commit | Modification | Effet mesuré |
|---|---|---|
| `aff5d5d` | **Termes additionnels inactifs ignorés.** En analyse de sécurité, chaque ligne déclenchable reçoit aussi des termes « branche ouverte » (P et Q, côtés 1 et 2) : ≈ 15 000 termes objet par tableau d'équations, presque tous inactifs, mais parcourus à chaque mismatch et à chaque remplissage de la jacobienne. `EquationArray` garde maintenant des tables compactes des seuls termes actifs, mises à jour de façon incrémentale à l'activation (tri conservé, donc résultats indépendants de l'historique). Les variables sont aussi parcourues dans l'ordre des éléments à l'initialisation. | 76,7 → 58,0 s (mismatch 830 → 200 µs par appel, jacobienne 1 160 → 650 µs) |
| `db71b65` | **Tensions des bus dans des tableaux du réseau.** Module et angle sont stockés dans des tableaux du `LfNetwork` indexés par numéro de bus, avec les mêmes accesseurs (`getV`, `setAngle`…) et la même arithmétique. L'initialisation de l'état (`VoltageInitializer.getMagnitude(network, num)`), la recopie dans le réseau et la restauration n'accèdent plus aux objets bus. Identifiant, poste et localisation des violations des bus sont mis en cache au lieu d'être relus dans la vue bus IIDM. | ≈ −3 s |
| `6cf6827` | **Accès direct à l'activité des termes** dans les boucles rapides du mismatch et de la jacobienne (tableau interne au lieu de `ArrayList.get` puis `TByteArrayList.getQuick` par entrée). | ≈ −5 à −8 s |

Essais abandonnés :

- **Vectoriser les termes shunt** : il n'y en a que 204 ; leur coût apparent venait d'une mauvaise
  attribution du profil.
- **Dérivées calculées à la demande** : séparer le calcul des transits de celui des dérivées
  (inutiles à la dernière itération) a coûté 7 s de plus. La passe sur les transits seuls coûte
  presque autant que la passe complète, car le coût est la lecture des données de branche, qui
  doublait.
- **Trigonométrie** : `FastMath.sinAndCos` (jafama) coûte ≈ 5 ns, plus rapide que `Math.sin` +
  `Math.cos` ; ce n'est pas le goulot.

Tests : 1489, 0 échec. Résultats identiques en NR pur (1 021 140 violations) et en configuration
par défaut (1 062 061 violations).

## Pistes 1 à 5 : derniers postes Java

Mesures en place par étape (chronométrage) et JFR précis, avant la série : KLU ≈ 15 s, transits ≈ 10,5 s
(dont 42 % juste après l'initialisation de l'état), violations ≈ 5,7 s, jacobienne ≈ 5,6 s, initialisation
et recopie de l'état ≈ 3,8 s, petits contrôles ≈ 1,8 s.

| Commit | Piste | Effet mesuré (passages isolés) |
|---|---|---|
| `16f3dd2` | **1. Transits du point de départ réutilisés.** Chaque contingence repart de l'état restauré du cas de base, identique bit à bit. `AcNetworkVector` garde une copie des tableaux de transits et de dérivées calculés pour un nouvel état complet ; si le même état est remis, il recopie ces tableaux et ne recalcule que les branches modifiées depuis (déclenchement, prises, connexion). Vérifié en recalculant tout à chaque réutilisation : 7421 réutilisations, toutes identiques bit à bit. | ≈ −2,4 s |
| `152470c` | **2. Pré-filtre vectorisé des violations.** Les plus petits seuils et les transits (via une interface générique `ArrayEvaluable`, implémentée par les termes vectorisés) sont lus dans des tableaux plats ; tensions et seuils des bus aussi. Les objets branche et bus ne sont touchés que pour les côtés ou bus au-dessus d'un seuil. Les violations de tension écartées par la violation de référence ne sont plus construites. `LimitViolationManager` reste générique (repli sur l'évaluation objet sinon). | ≈ −3,8 s |
| — | **3. Remplissage direct de la matrice LU : non fait.** La jacobienne émet ses valeurs dans l'ordre des dérivées, la matrice LU a ses lignes triées : la correspondance n'est jamais l'identité. Il faudrait changer l'ordre d'émission dans `EquationArray` pour ≈ 1 s. | — |
| `7258e33` | **4. Variables en tableau plat** (type, élément, ligne), mis en cache par l'index, pour l'initialisation de l'état, la recopie et le contrôle d'état non réaliste. **5. Compensateurs avec automate mis en cache** dans `MonitoringVoltageOuterLoop.isNeeded`, au lieu de parcourir tous les générateurs. | ≈ −0,7 s |

Tests : 1489, 0 échec. Résultats identiques en NR pur et en configuration par défaut.

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
| `-Dolf.networkState.checkIncrementalRestore=true` | vérifie chaque restauration incrémentale |
| `-Dolf.networkState.noIncrementalRestore=true` | force la restauration complète |

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
| `780015f` | Patch powsybl-math-native amélioré et comparaison mono-thread |
| `839ae9b` | Sauvegarde/restauration d'état vectorisée (tableaux plats) |
| `42d48c3` | Restauration d'état incrémentale (setters qui marquent les éléments modifiés) |
| `aff5d5d` | Termes additionnels inactifs ignorés dans le mismatch et la jacobienne |
| `db71b65` | Tensions des bus dans des tableaux du réseau, identifiants de bus en cache |
| `6cf6827` | Accès direct à l'activité des termes dans les boucles rapides |
| `16f3dd2` | Transits du point de départ réutilisés (état identique) |
| `152470c` | Pré-filtre vectorisé de la détection des violations |
| `7258e33` | Variables en tableau plat, compensateurs avec automate en cache |
