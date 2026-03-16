# speed-intranet

Outil de mesure de la **bande passante effective** d'un réseau local.  
*Local Network Bandwidth Measurement Tool*

---

## Fonctionnalités

| Fonctionnalité | Description |
|---|---|
| **Petits fichiers** | 200 × 1 Kio — mesure l'overhead lié aux nombreux transferts |
| **Fichiers moyens** | 1 × 20 Mio — charge de travail intermédiaire typique |
| **Gros fichier** | 1 × 300 Mio — débit de pointe en saturation |
| **Bidirectionnel** | Upload (terminal 1 → annexe) et Download (annexe → terminal 1) |
| **Latence TCP** | Mesure du RTT avant chaque test |
| **Multi-terminaux** | Teste automatiquement tous les terminaux listés dans `config.ini` |
| **Marqueurs de temps** | Ping TCP avant/après chaque transfert pour calculer le temps exact |

---

## Architecture

```
Terminal 1 (contrôleur)                Terminal annexe 2          Terminal annexe 3
──────────────────────────             ──────────────────         ──────────────────
./speedtest-linux-x86_64 auto    ←→   ./speedtest-linux-x86_64   ./speedtest-linux-x86_64
  --config config.ini                   server                      server
```

- **Terminal 1** exécute le mode `auto` (ou `client`) : il initie tous les tests.
- **Chaque terminal annexe** exécute le mode `server` : il attend les connexions.
- Les tests s'effectuent **uniquement entre Terminal 1 et chaque terminal annexe** (pas entre terminaux annexes entre eux).

---

## Installation — sans Python (exécutable autonome)

> **Aucune installation de Python n'est nécessaire sur les machines cibles.**  
> Téléchargez simplement le binaire correspondant à votre système et rendez-le exécutable.

### Téléchargement

| Système | Fichier |
|---|---|
| **Linux 64-bit** (x86_64) | `dist/speedtest-linux-x86_64` |

```bash
# Cloner le dépôt (ou télécharger uniquement le binaire)
git clone https://github.com/sebastienipharma/speed-intranet.git
cd speed-intranet

# Rendre l'exécutable... exécutable
chmod +x dist/speedtest-linux-x86_64

# Vérification
./dist/speedtest-linux-x86_64 --help
```

> **Note :** Le binaire `dist/speedtest-linux-x86_64` a été compilé pour Linux x86_64
> (glibc ≥ 2.17, compatible Ubuntu 16.04+, Debian 9+, CentOS 7+).  
> Pour d'autres plateformes (macOS, Windows, ARM), voir la section [Compiler soi-même](#compiler-soi-même).

---

## Utilisation

Remplacez `python speedtest.py` par `./dist/speedtest-linux-x86_64` dans tous les exemples
(ou copiez le binaire dans `/usr/local/bin/speedtest` pour l'utiliser sans chemin).

### 1. Mode serveur (sur chaque terminal annexe)

```bash
./dist/speedtest-linux-x86_64 server
# Port par défaut : 5201
./dist/speedtest-linux-x86_64 server --port 5201
```

### 2. Mode client (sur Terminal 1, test vers un seul terminal)

```bash
# Test complet (upload + download, tous les formats de fichiers)
./dist/speedtest-linux-x86_64 client --server 192.168.1.2

# Uniquement les petits fichiers, dans les deux sens
./dist/speedtest-linux-x86_64 client --server 192.168.1.2 --tests small

# Uniquement l'upload (Terminal 1 → terminal annexe)
./dist/speedtest-linux-x86_64 client --server 192.168.1.2 --direction upload

# Petits et moyens fichiers, réception seulement
./dist/speedtest-linux-x86_64 client --server 192.168.1.2 --tests small,medium --direction download
```

### 3. Mode automatique (sur Terminal 1, test tous les terminaux)

Éditez d'abord `config.ini` pour y mettre les adresses IP de vos terminaux, puis :

```bash
./dist/speedtest-linux-x86_64 auto
# ou avec un fichier de config personnalisé
./dist/speedtest-linux-x86_64 auto --config config.ini
```

---

## Fichier de configuration (`config.ini`)

```ini
[network]
# Port TCP utilisé (même valeur sur toutes les machines)
port = 5201

# IP des terminaux annexes (chacun tourne en mode "server")
terminals = 192.168.1.2, 192.168.1.3

[tests]
# small | medium | large | all  (ou combinaison : small,medium)
test_types = all

# upload | download | both
direction = both
```

---

## Options de la ligne de commande

```
usage: speedtest.py [-h] [--server IP] [--port PORT] [--config FILE]
                    [--tests TYPES] [--direction {upload,download,both}]
                    {server,client,auto}

--tests :
  small           200 fichiers de 1 Kio
  medium          1 fichier de 20 Mio
  large           1 fichier de 300 Mio
  all             Tous les tests (défaut)
  small,medium    Combinaison possible

--direction :
  upload          Terminal 1 → terminal annexe seulement
  download        Terminal annexe → Terminal 1 seulement
  both            Les deux sens (défaut)
```

---

## Protocole de mesure

```
Émetteur                         Récepteur
────────                         ─────────
  │── PING ──────────────────────►│
  │◄── PONG ───────────────────── │  ← t_start enregistré
  │                               │
  │═══ Transfert des fichiers ════►│
  │                               │
  │── PING ──────────────────────►│
  │◄── PONG ───────────────────── │  ← t_end enregistré
  │                               │
  elapsed = t_end − t_start
  débit   = octets_transférés × 8 / elapsed  [Mbps]
```

Le **premier ping** marque le début du test (réseau opérationnel, latence mesurée).  
Le **second ping** marque la fin du test (tous les fichiers ont bien été transmis).  
Le delta donne le temps de transfert effectif, duquel on déduit le débit réel.

---

## Exemple de sortie

```
════════════════════════════════════════════════════════════════════════
  speed-intranet v1.0.0 — Mesure de bande passante réseau local
════════════════════════════════════════════════════════════════════════

  Serveur cible : 192.168.1.2:5201
  Tests         : small, medium, large
  Direction     : both
  Latence TCP   : 0.3 ms

────────────────────────────────────────────────────────────────────────
  → Envoi    (small)…
  → [UPLOAD  ] small  : 200 fichier(s), 200.0 Kio en 0.021 s  →  9.07 Mio/s (75.5 Mbps)  [latence: 0.3 ms]
  ← Réception (small)…
  ← [DOWNLOAD] small  : 200 fichier(s), 200.0 Kio en 0.019 s  →  10.03 Mio/s (83.5 Mbps)  [latence: 0.3 ms]
  → Envoi    (medium)…
  → [UPLOAD  ] medium : 1 fichier(s), 20.0 Mio en 1.823 s  →  10.97 Mio/s (91.3 Mbps)  [latence: 0.3 ms]
  ...

  RÉSUMÉ — 192.168.1.2
  ────────────────────────────────────────────────────────────────────────
  Débit moyen  upload   : 89.4 Mbps
  Débit moyen  download : 91.1 Mbps
════════════════════════════════════════════════════════════════════════
```

---

## Compiler soi-même

Si vous avez besoin d'un binaire pour une autre plateforme (macOS, Windows, ARM…),
compilez le depuis la source sur la machine cible :

```bash
# 1. Prérequis : Python 3.7+ et pip (uniquement sur la machine de build)
# 2. Cloner le dépôt
git clone https://github.com/sebastienipharma/speed-intranet.git
cd speed-intranet

# 3. Lancer le script de build (installe PyInstaller automatiquement)
chmod +x build.sh
./build.sh
# → produit dist/speedtest-<plateforme>-<architecture>
```

Le binaire produit peut ensuite être copié sur n'importe quelle machine du même type
**sans Python**.

> **Windows** : le script `build.sh` fonctionne sous Git Bash / WSL.
> Le binaire `.exe` produit est autonome.

---

## Utilisation avec Python (optionnel)

Si Python 3.7+ est déjà disponible sur vos machines, vous pouvez aussi utiliser
directement le script source sans compilation :

```bash
python3 speedtest.py server
python3 speedtest.py client --server 192.168.1.2
python3 speedtest.py auto --config config.ini
```

---

## Licence

MIT — voir [LICENSE](LICENSE)
