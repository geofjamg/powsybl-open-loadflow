# Résumé : analyse de sécurité N-1, OpenLoadFlow vs lightsim2grid

Le rapport détaillé et les scripts sont dans [README.md](README.md).

## Contexte

- **Réseau** : MATPOWER `case6515rte`, converti en IIDM avec le convertisseur MATPOWER de powsybl
  (6515 bus, 7422 lignes, 1615 transformateurs).
- **Même réseau des deux côtés** : lightsim2grid 1.3.0 est construit depuis le *même* IIDM
  (`init_from_pypowsybl`). En cas de base, l'écart maximal de transit vaut 0,10 MW.
- **Contingences** : N-1 sur les 7422 lignes, dont 2162 créent un îlot.
- **Solveur** : les deux outils utilisent KLU. Sur les mêmes matrices, les deux bibliothèques
  donnent des temps identiques : **la bibliothèque KLU n'est pas en cause**.

## Benchmark (4 vCPU)

| Configuration | Contingences | OLF | lightsim2grid | Ratio |
|---|---|---|---|---|
| NR pur (slack unique, sans limites réactives), 1 thread | 7422 | ≈ 260 s | 43 s | ×6 |
| NR pur, contingences sans îlot | 5260 | 146 s | 33–38 s | ×4 |
| NR pur, 4 threads | 7422 | 120 s | 12,8 s | ×9 |
| Défaut (boucles externes) | 7422 | ≈ 470 s | 254 s | ×1,9 |

Le critère d'arrêt d'OLF est plus lâche (1,3 itération par contingence, contre 2,0 pour
lightsim2grid) : il ne joue pas en défaveur d'OLF.

## D'où vient l'écart (NR pur, ≈ 260 s)

KLU représente ≈ 105 s côté OLF, contre ≈ 23 s côté lightsim2grid. Ce dernier ne fait
**qu'une seule factorisation complète**, puis uniquement des `klu_refactor`.

| # | Cause | Coût estimé |
|---|---|---|
| 1 | **Contingences créant un îlot** : les équations des bus isolés sont retirées, la structure change. OLF reconstruit alors la jacobienne Java puis refait `klu_analyze` + `klu_factor`, et recommence après la restauration de l'état (3380 reconstructions ; 2 seulement sans îlot). lightsim2grid masque ces bus par des équations identité, sans changer le motif creux. | ≈ 86 s |
| 2 | **`klu_factor` complet à chaque contingence au lieu de `klu_refactor`** : le passage en `VALUES_AND_ZEROS_INVALID` appelle `lu.update(false)`, alors que le motif creux est conservé. | ≈ 34 s |
| 3 | **Numérotation de la jacobienne en mode vectorisé (défaut)** : colonnes [V, tout P, tout Q] contre lignes [φ₀, V₀, φ₁…]. La diagonale est vide, KLU (BTF) trouve un mauvais appariement et la factorisation produit **+44 % de remplissage**. Toutes les opérations KLU sont ≈ ×2 plus lentes, et `klu_refactor` échoue souvent. | ≈ ×2 sur tout KLU |
| 4 | **Travail Java par itération** : remplissage de la jacobienne terme par terme, mismatch, recalcul des transits de *toutes* les branches à chaque itération. | ≈ 90 s |
| 5 | **Fonctions propres à l'analyse de sécurité** : détection des violations (plus d'un million d'objets créés ici), sauvegarde et restauration complètes de l'état réseau à chaque contingence. | ≈ 40 s |

Le point 3 se mesure sur une même matrice :

| Jacobienne | nnz(L+U) | `klu_refactor` | `klu_analyze` | `klu_factor` |
|---|---|---|---|---|
| OLF vectorisée (défaut) | 211 114 | 2,6 ms | 9,0 ms | 7,6 ms |
| OLF réalignée ou non vectorisée | ≈ 149 000 | 1,3 ms | ≈ 4 ms | ≈ 4 ms |
| lightsim2grid | 146 644 | 1,4 ms | 4,2 ms | 5,4 ms |

## Expériences de correction (non intégrées dans `src/`)

- **`klu_refactor` au lieu de `klu_factor` sur `VALUES_AND_ZEROS_INVALID`** : le temps des
  contingences sans îlot passe de 146 à 128 s (−12 %), avec des résultats identiques. Il reste
  1507 échecs de refactorisation, conséquence du point 3.
- **Même correctif avec une jacobienne alignée** : 0 échec, et le coût KLU des contingences sans
  îlot passe de 55 s à 19 s, au niveau de lightsim2grid.
- **Le mode non vectorisé n'est pas une solution** : le Java y est plus lent (373 s au total). Il
  faut garder la vectorisation **et** aligner la numérotation.

## Pistes pour OLF, par gain estimé

1. Garder le motif creux pour les bus isolés et les basculements PV↔PQ (équation identité) :
   **≈ −80 s**, et davantage en configuration par défaut.
2. Aligner la numérotation variables/équations en mode vectorisé : **≈ −40 s**.
3. Utiliser `klu_refactor` (avec le contrôle `rgrowth` et son repli) après une contingence.
4. Calculer les transits paresseusement, restaurer l'état de façon incrémentale et limiter la
   détection des violations aux éléments surveillés : **≈ −30 à −50 s**.

Les points 1 à 3 ramèneraient la partie KLU d'OLF au niveau de lightsim2grid. L'écart restant
viendrait alors du Java par itération.
