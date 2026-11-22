# Lunettes Claude

App Android personnelle qui remplace Meta AI par Claude sur des Ray-Ban Meta : tu poses une question
à voix haute, l'app prend une image avec la caméra des lunettes, l'envoie à Claude avec ta question
et te lit la réponse en français dans les branches.

Dérivée de [Tupo Vision](https://github.com/KeonyRus/tupo_vision) (licence MIT, voir `LICENSE`) :
la connexion aux lunettes, la veille par geste et la boucle voix viennent de ce projet ; ChatGPT est
remplacé par l'API Claude.

## Fonctionnement

```
appui long sur la branche (ou « Hey Meta, mets de la musique »)
  → Spotify démarre → l'app le coupe, bip, conversation ouverte
  → reconnaissance vocale sur le téléphone (fr-FR)
  → question + une image des lunettes → API Claude (Haiku par défaut)
  → synthèse vocale du téléphone → haut-parleurs des lunettes
  → arrêt automatique après 45 s sans question
```

## Garde-fous de coût

- Plafond de dépenses mensuel à fixer dans la console Anthropic (la vraie garantie).
- Quota de questions par mois dans l'app (300 par défaut).
- Réponses limitées à 500 jetons, une seule image par question, historique de 6 échanges.
- Compteur mensuel des jetons affiché dans l'app.

## Installation

1. Télécharger `lunettes-claude.apk` depuis la release `latest` de ce dépôt, sur le téléphone.
2. Meta AI → Paramètres → Infos sur l'app → toucher 5 fois la version → mode développeur → Activer,
   puis installer Device Access Toolkit si proposé.
3. Ouvrir Lunettes Claude → Accorder les autorisations → Lier les lunettes via Meta AI.
4. Coller la clé API (console.anthropic.com) → Enregistrer.
5. Démarrer une première conversation depuis l'app et choisir « Toujours autoriser » pour la caméra.
6. Pour le geste : Meta AI → appui long = Musique (Spotify) ; dans l'app, accès aux notifications
   puis interrupteur « Veille », lunettes sur le nez.

## Compilation

Chaque push sur `main` compile l'APK avec GitHub Actions et le publie dans la release `latest`.
Le résultat de compilation (journal, erreurs) est aussi poussé sur la branche `ci`.
