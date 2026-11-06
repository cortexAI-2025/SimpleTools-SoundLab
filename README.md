# SimpleTools SoundLab — by AIWORKPAY

Application Android 1.0.0 (code 1, Android 10 à 15) d’observation acoustique hors ligne. Interface française et anglaise. Acquisition au premier plan via le microphone et conservation locale des seules mesures numériques.

## Utilisation

**Mesurer** ouvre le sonomètre. Les autres vues partagent l’acquisition en cours. La taille FFT se choisit avant le démarrage. Pause arrête le microphone et exclut le temps de pause de la durée mesurée. Terminer rend la session disponible dans l’historique. La session est limitée à 60 minutes de mesure active et son historique est enregistré par lots d’environ une seconde. À la prochaine ouverture, une session interrompue par la mort du processus est clôturée au dernier lot enregistré. Le nom et les notes sont modifiables depuis l’historique. PDF, CSV et JSON peuvent être enregistrés via le sélecteur de documents ou partagés volontairement.

## Mesures et limites

- PCM mono 16 bits, normalisé en divisant par 32768. Android est interrogé pour UNPROCESSED, puis VOICE_RECOGNITION et MIC. Fréquences testées à l’ouverture : 48, 44,1 et 16 kHz. Un changement de route suspend la session et nécessite de conserver la même configuration pour reprendre.
- Le niveau instantané est calculé sur un bloc d’environ 125 ms. Leq dBFS agrège les carrés des échantillons pondérés par leur nombre, avec un plancher numérique de −120 dBFS pour le silence. Une sinusoïde de crête 0,5 donne environ −9,03 dBFS RMS.
- FFT radix-2 à 1024, 2048, 4096 ou 8192 points. La fenêtre de Hann périodique réduit la fuite spectrale. Le spectre unilatéral divise le module par la somme de la fenêtre et double les bins intérieurs. Les valeurs sont des amplitudes de crête en dBFS. Une sinusoïde centrée sur un bin et de crête 0,5 donne −6,02 dBFS au bin central. Le spectre va de 0 à Nyquist. Les FFT utilisent les derniers échantillons disponibles et se recouvrent lorsque leur durée dépasse le pas de mise à jour.
- Les pics utilisent des maxima locaux, une marge de 12 dB au-dessus du bruit médian (plancher −95 dBFS), trois bins de séparation et une interpolation parabolique en dB. Les fréquences sont des estimations. L’historique conserve jusqu’à trois pics par bloc et leurs amplitudes.
- Calibration indicative sur trois secondes, avec une variation maximale de 3 dB, rejet de la saturation et prise en compte de la source, du périphérique et de la fréquence d’échantillonnage. L’offset n’assure pas la correction de la réponse en fréquence, de la directivité ou des traitements internes. Les comparaisons SPL supposent des conditions acoustiques communes et un profil identique. L’application ne fournit aucune certification acoustique ou industrielle.
- L’export JSON v1 est la représentation numérique versionnée exploitable par une future intégration locale. Aucun fournisseur IA ni protocole réseau dans cette version. Les évolutions Room doivent ajouter une migration explicite avant d’incrémenter la version. La base n’utilise aucun repli destructif.

## Build et vérification

JDK 17, Gradle 8.10.2, AGP 8.7.3, Android SDK 35 et Kotlin 2.1.10.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Le workflow Android vérifie la signature, le package, la version et les permissions du binaire, puis publie le fichier de contrôle SHA-256 avec l’artefact **SimpleTools-SoundLab-Android-APK**. Les tests d’émulateur utilisent des signaux déterministes et un flux substitué pour les scénarios de cycle de vie. Voir [recette Galaxy A14](RECETTE-GALAXY-A14.md).
