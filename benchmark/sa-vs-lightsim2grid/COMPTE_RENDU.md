# Compte rendu : accélération de l'analyse de sécurité AC d'OpenLoadFlow

Branche : `claude/clever-edison-qs6x0z`. Les commits sont listés à la fin.

L'objectif était de rapprocher l'analyse de sécurité N-1 d'OpenLoadFlow (OLF) de celle de
lightsim2grid (LS2G). Le diagnostic de départ est dans [RESUME.md](RESUME.md) et
[README.md](README.md).

## Résultat

Le cas testé est `case6515rte` en N-1 sur les 7422 lignes, dont 2162 créent un îlot, sur 4 vCPU.
Toutes les mesures ci-dessous ont été prises le même jour, sur la même machine. Pour OLF, le code
d'origine et la branche ont été **exécutés en alternance (A/B)**, car le bruit de la machine est
important : la même mesure varie de ±15 % d'une heure à l'autre.

| Configuration | OLF d'origine | **OLF branche** | Gain | LS2G | Écart restant, OLF / LS2G |
|---|---|---|---|---|---|
| NR pur, 1 thread, 7422 contingences (îlots calculés) | 357 s | **211 s** | ×1,7 | 47,4 s (51,5 s avec violations) | ×4,5 (avant : ×7,5) |
| NR pur, 4 threads, 7422 contingences | 147 s | **76 s** | ×1,9 | 15,8 s | ×4,8 (avant : ×9,3) |
| NR pur, 1 thread, 5260 contingences sans îlot | 186 s | **94 s** | ×2,0 | 30,7 s ¹ | ×3,1 (avant : ×6,1) |
| Paramètres par défaut (boucles externes), 1 thread | 532 s | **393 s** | ×1,35 | 262 s ² | ×1,5 (avant : ×2,0) |

¹ LS2G ignore les contingences qui créent un îlot.
² LS2G `NROuter_KLU`, avec ses boucles externes qui reproduisent celles d'OLF.

**Les résultats sont strictement identiques** avant et après les modifications : mêmes statuts de
convergence, et même nombre de violations (1 021 140 en NR pur, 1 062 061 en configuration par
défaut). Les tests OLF passent : **1487 tests, 0 échec**, comme sur le code d'origine.

**L'objectif « aussi rapide que lightsim2grid » n'est pas atteint.** L'écart est divisé par 1,7 à 2.
La partie solveur (KLU) est maintenant au niveau de LS2G. Ce qui reste vient du code Java autour du
solveur, et surtout des contingences qui créent un îlot (voir plus bas).

## Modifications réalisées

### 1. Numérotation de la jacobienne alignée et CSC triée (gain sur toutes les opérations KLU)

- **Problème.** En mode vectorisé, les équations (colonnes) sont rangées
  [équations simples, tout P, tout Q] et les variables (lignes) par bus, sous la forme φ₀, V₀, φ₁…
  La diagonale était vide. KLU, via BTF puis AMD, produisait alors **44 % de remplissage en plus**,
  et toutes ses opérations coûtaient environ deux fois plus.
- **Correction.**
  - `EquationSystem` accepte un *provider* qui associe à chaque type d'équation la variable
    diagonale du même élément. Il est défini pour l'AC dans `AcEquationSystemCreator` : P→φ, Q→V,
    V→V, etc.
  - `EquationSystemIndex` numérote les variables dans l'ordre des colonnes.
  - Le vecteur d'état est permuté si une renumérotation intervient en cours de calcul.
  - `AcNetworkVector` invalide son cache de lignes lors d'un changement d'ordre des équations.
- **Indices triés.** KLU reçoit désormais une copie CSC à indices de ligne triés, car son ordre de
  pivotage en dépend.
- **Effet.** Sur une même matrice, le remplissage passe de 211 114 à 148 520 non-zéros
  (146 644 pour LS2G). `klu_refactor` passe de 2,6 à 1,4 ms, `klu_analyze` de 9 à 4,6 ms.

### 2. `klu_refactor` au lieu de `klu_factor` après un déclenchement

- **Problème.** La désactivation des termes d'une ligne (`VALUES_AND_ZEROS_INVALID`) déclenchait une
  factorisation numérique complète à chaque contingence.
- **Correction.** Le motif creux est conservé, donc `update(true)` suffit : `klu_refactor` avec
  contrôle `rgrowth`, et repli automatique sur `klu_factor` si besoin.
- **Effet.** Grâce à l'alignement du point 1, il ne reste **aucun échec de refactorisation**
  (1507 échecs sans alignement).

### 3. Structure LU conservée quand des bus sont isolés

Nouvelle classe `JacobianLuStructure`. Quand la jacobienne perd des équations et des variables (bus
d'un îlot), ses valeurs sont projetées sur la structure LU précédente :

- les équations et variables retirées sont remplacées par l'identité (1 sur la diagonale, second
  membre nul) ;
- le second membre et la solution sont projetés ;
- la LU n'est que **refactorisée**, aussi bien sur la contingence que lors de la restauration.

Effet : sur les 7422 contingences, on passe de **3380 à 2** factorisations complètes, et le temps
KLU total de ≈ 105 s à ≈ 26 s, soit le niveau de LS2G (≈ 23 s).

### 4. Réduction du travail Java

- **Remplissage rapide de la jacobienne.** Chaque entrée du vecteur de dérivées mémorise l'indice
  de la valeur de matrice qu'elle alimente. Les mises à jour suivantes font un simple
  « scatter-add », sans fusion de lignes ni lambda par élément. Le remplissage passe de 1,74 à
  0,60 ms.
- **Courants de branche paresseux.** Ils étaient recalculés (`hypot`) pour toutes les branches à
  chaque itération ; ils ne sont plus calculés qu'à la lecture.
- **`EquationArray.der` réécrit pour les termes non vectorisés**, sans parcours de `TreeMap` par
  ligne ni allocation par élément.
- **Sauvegarde et restauration de l'état des générateurs** dans des tableaux, au lieu de tables
  indexées par identifiant.
- **Reconstruction de structure moins chère** : table de hachage pour la numérotation alignée, et
  matrice pré-dimensionnée.

### 5. Correction de bug

L'alignement a mis en évidence un bug latent dans `PiModelArray.shiftOneTapPositionToChangeA1`.
Après avoir avancé d'une prise, la méthode pouvait revenir à la prise de départ dès que α n'était
pas exactement 0 (par exemple 1e-19). Il est corrigé.

### Tests modifiés

- `SecondaryVoltageControlTest` : le rapport attendu a été actualisé. L'écart porte sur le
  6ᵉ chiffre de q (24,012059 au lieu de 24,012062), dans la tolérance de convergence.
- `EquationArrayTest.testSparseReproducibility` : la structure de matrice attendue a été mise à jour
  (nouvelle numérotation). Sa reproductibilité a été vérifiée sur deux exécutions.

## Pourquoi l'écart reste d'environ ×4

En NR pur sur 1 thread (211 s), le temps se répartit ainsi :

| Poste | Estimation | Commentaire |
|---|---|---|
| **2162 contingences créant un îlot** | **≈ 115 s** | ≈ 55 ms chacune, contre ≈ 18 ms pour une contingence sans îlot |
| Itérations NR en Java (mismatch, mise à jour de l'état et des transits, remplissage) | ≈ 40 s | |
| KLU | ≈ 26 s | équivalent à LS2G |
| Détection des violations | ≈ 25 s | 1 M violations produites ; LS2G les calcule en ≈ 4 s supplémentaires |
| Restauration de l'état (`NetworkState.restore`) | ≈ 15 s | |

**Principale piste restante : les contingences qui créent un îlot.** OLF désactive les équations
et variables des bus isolés, ce qui change la structure du système. À chaque îlot, puis à chaque
restauration, il faut donc reconstruire :

- l'index d'équations et la renumérotation ;
- la jacobienne Java (`initDer`) ;
- les vecteurs de mismatch et de cibles ;
- les caches de `AcNetworkVector`.

KLU ne coûte plus rien sur ce chemin, mais ce Java représente ≈ 15 ms par reconstruction, deux fois
par contingence. LS2G, lui, **masque** les bus isolés par des équations identité *sans changer la
structure*.

L'équivalent dans OLF serait de garder dans l'index des colonnes et lignes « fantômes » pour les
équations et variables désactivées (identité dans la jacobienne, zéro dans les mismatches). C'est
un changement de sémantique profond : `isActive`, `EquationArray.elementActive`, les vecteurs, les
solveurs et les boucles externes sont concernés. Je ne l'ai pas fait sans revue dédiée. Le gain
estimé est de ≈ 80 à 100 s, ce qui ramènerait OLF à ≈ 110–130 s, soit environ ×2,5 de LS2G.

**Autres pistes.**

- **Réduire le Java par itération.** Évaluer les mismatches directement à partir des tableaux de
  transits, et fusionner la mise à jour d'état avec l'évaluation.
- **Restauration incrémentale.** Ne restaurer que les éléments modifiés par la contingence, plus les
  tensions.
- **Contrôle `rgrowth` dans powsybl-math-native.** Il coûte ≈ 0,35 ms par refactorisation ;
  LS2G ne le fait pas par défaut. Le rendre optionnel demande une évolution de la bibliothèque
  native, hors de ce dépôt.

## Reproduire

Le protocole et les scripts sont décrits dans le [README](README.md).

Pour l'A/B, chaque configuration est lancée successivement sur le code d'origine et sur la
branche, avec le même classpath de dépendances :

```bash
java -cp <classes>:<deps>:<bench> bench.OlfN1Bench case6515rte.xiidm <cfg> 1
```

Configurations :

| Configuration | Signification |
|---|---|
| `nr` | NR pur, 1 thread |
| `nr_t4` | NR pur, 4 threads |
| `default` | paramètres par défaut |
| `-Dexclude=islanding_lines.txt` | exclut les contingences créant un îlot |
| `-Donly=…` | ne garde que les contingences listées |
| suffixe `_count` | compte les appels KLU |

## Commits

| Commit | Contenu |
|---|---|
| `287a77a` | LU de la jacobienne AC : numérotation alignée, CSC triée, `klu_refactor` après déclenchement, correction de `PiModelArray` |
| `1947e8a` | Structure LU conservée quand la jacobienne rétrécit (îlots) |
| `a99b6d8` | Courants paresseux, `EquationArray.der` sans `TreeMap` par ligne, états des générateurs en tableaux |
| `529723b` | Remplissage rapide des valeurs de jacobienne (scatter-add) |
| `bf33b1c` | Reconstruction de structure moins chère |
