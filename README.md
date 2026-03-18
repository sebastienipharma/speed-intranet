# speed-intranet (Java)

Outil de mesure de bande passante intranet, désormais maintenu en version Java.

## Etat du projet

- L'implémentation Python a été retirée du dépôt.
- Le projet est désormais centré sur l'application Java (CLI + GUI).

## Démarrage rapide

```bash
cd java
mvn clean package
java -jar target/speed-intranet-java8-1.0.6.jar
```

Cela ouvre l'interface graphique cliquable.

## Utilisation CLI (Java)

```bash
java -jar target/speed-intranet-java8-1.0.5.jar server --port 5201
java -jar target/speed-intranet-java8-1.0.5.jar client --server 192.168.1.2 --tests all --direction both
java -jar target/speed-intranet-java8-1.0.5.jar auto --config ../config.ini --output results.csv
```

## Configuration

Le fichier `config.ini` à la racine est utilisé pour le mode `auto`.

## Documentation

La documentation détaillée Java est dans `java/README.md`.

## Licence

MIT - voir [LICENSE](LICENSE)





