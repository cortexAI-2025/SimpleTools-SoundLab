# Recette physique — Samsung Galaxy A14, Android 15

Consigner le SHA du commit, l’empreinte SHA-256 du fichier, le modèle, le numéro de build Android, le jour, et un résultat par scénario. Utiliser un volume d’écoute habituel et une source distante raisonnable.

1. Télécharger l’artefact de la CI, extraire le ZIP, comparer le SHA-256 et installer le fichier APK debug sur le Galaxy A14.
2. Ouvrir SoundLab. Avant toute mesure, vérifier l’absence d’indicateur microphone et de valeur SPL.
3. Appuyer sur **Mesurer** et refuser la permission. Vérifier le message, puis accorder la permission au prochain essai ou via les réglages Android.
4. Observer le niveau dBFS en ambiance calme, puis en parlant. Vérifier que le niveau varie et que la mention dBFS est visible.
5. Ouvrir l’oscilloscope, changer la fenêtre et figer l’affichage. Observer un signal évolutif, puis une trace immobile en pause visuelle.
6. Jouer une tonalité modérée connue, par exemple 1 kHz depuis un autre appareil. Ouvrir Spectre FFT, vérifier le pic voisin de 1 kHz, Nyquist, la résolution et les échelles. Observer les pics secondaires et leur historique.
7. Tester le calme puis une ambiance plus bruyante à volume sûr. Un silence acoustique ordinaire peut contenir du bruit de fond.
8. Mettre la session en pause. Vérifier l’arrêt du microphone et du compteur actif. Reprendre, changer de module, puis terminer. Consulter et renommer la session, ajouter des notes et vérifier la persistance après relance.
9. Faire une deuxième session comparable. Vérifier les écarts et les courbes. Répéter avec une configuration différente et vérifier l’avertissement.
10. Exporter CSV, JSON et PDF. Ouvrir chacun et comparer les métadonnées et valeurs à l’historique. Vérifier le PDF et ses graphiques, y compris sur ordinateur. Tester le partage volontaire.
11. Mesurer une source stable avec un sonomètre de référence. Entrer la valeur SPL, enregistrer le profil et refaire une session compatible. Vérifier les deux unités, la mention « indicatif », puis réinitialiser.
12. Pendant une mesure, verrouiller l’écran, revenir à l’accueil Android et faire pivoter l’écran lors de trois essais séparés. Vérifier l’arrêt du microphone hors premier plan et la reprise manuelle.
13. Révoquer la permission et vérifier le refus. Activer le mode avion et vérifier à nouveau l’acquisition et l’export local. Signaler tout crash, perte de données ou microphone encore actif.
