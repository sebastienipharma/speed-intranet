#!/usr/bin/env python3
"""
speed-intranet v1.0.0

Outil de mesure de la bande passante effective d'un réseau local.
Local Network Bandwidth Measurement Tool.

Architecture:
    - Chaque machine secondaire (terminal annexe) tourne en mode SERVEUR.
    - La machine principale (terminal 1) tourne en mode AUTO ou CLIENT.
    - Le mode AUTO lit config.ini et teste chaque terminal annexe dans les
      deux sens (upload et download).

Protocole TCP:
    Chaque message sur le réseau a le format :
        [4 octets : commande][8 octets : longueur payload][payload]
    Pour les transferts de fichiers, après ce header suit directement le
    contenu brut du fichier (longueur annoncée dans le header).

Flux de mesure (upload : terminal 1 → terminal annexe) :
    1. Le client envoie CMD_UPLOAD + type de test au serveur.
    2. Le client envoie CMD_PING  → le serveur répond CMD_PONG.
       Le client enregistre t_start à la réception du PONG.
    3. Le client envoie tous les fichiers de test.
    4. Le client envoie CMD_PING  → le serveur répond CMD_PONG.
       Le client enregistre t_end à la réception du PONG.
    5. elapsed = t_end - t_start, puis calcul du débit.

Flux de mesure (download : terminal annexe → terminal 1) :
    1. Le client envoie CMD_DOWNLOAD + type de test au serveur.
    2. Le client envoie CMD_PING  → le serveur répond CMD_PONG.
       Le client enregistre t_start.
    3. Le serveur envoie tous les fichiers de test, puis CMD_DONE.
    4. Le client, après avoir reçu CMD_DONE, envoie CMD_PING.
       Le serveur répond CMD_PONG ; le client enregistre t_end.
    5. elapsed = t_end - t_start, puis calcul du débit.
"""

import argparse
import configparser
import csv
import json
import os
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

try:
    from _version import VERSION
except ImportError:
    VERSION = "1.00"

# ---------------------------------------------------------------------------
# Constantes
# ---------------------------------------------------------------------------
DEFAULT_PORT = 5201
BUFFER_SIZE = 65_536          # 64 Kio par lecture/écriture
DEFAULT_TIMEOUT = 10.0

# Tailles des fichiers de test
SMALL_FILE_SIZE = 1 * 1_024                # 1 Kio
SMALL_FILE_COUNT = 200                     # 200 petits fichiers
MEDIUM_FILE_SIZE = 20 * 1_048_576          # 20 Mio
LARGE_FILE_SIZE = 300 * 1_048_576          # 300 Mio

# Identifiants des types de test
TEST_SMALL = "small"
TEST_MEDIUM = "medium"
TEST_LARGE = "large"
ALL_TESTS = [TEST_SMALL, TEST_MEDIUM, TEST_LARGE]

# Commandes du protocole (4 octets chacune)
CMD_PING = b"PING"
CMD_PONG = b"PONG"
CMD_UPLOAD = b"UPLD"    # client → serveur : « je vais vous envoyer des fichiers »
CMD_DOWNLOAD = b"DNLD"  # client → serveur : « envoyez-moi des fichiers »
CMD_FILE = b"FILE"      # suivi de 8 octets = taille, puis les données brutes
CMD_DONE = b"DONE"      # fin des transferts
CMD_RESULT = b"RSLT"    # suivi d'un JSON de résultats
CMD_ERROR = b"ERRR"     # suivi d'un message d'erreur
CMD_BYE = b"BYE_"       # fermeture de session


# ---------------------------------------------------------------------------
# Couche protocole
# ---------------------------------------------------------------------------

class ProtocolError(Exception):
    """Erreur de protocole réseau."""


def _recv_exactly(sock: socket.socket, n: int) -> bytes:
    """Lit exactement *n* octets depuis le socket ; lève ConnectionError si la
    connexion est fermée prématurément."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Connexion fermée prématurément.")
        buf.extend(chunk)
    return bytes(buf)


def send_cmd(sock: socket.socket, cmd: bytes, payload: bytes = b"") -> None:
    """Envoie une commande avec un payload optionnel.

    Format : [4 octets cmd][8 octets longueur][payload]
    """
    header = cmd + struct.pack(">Q", len(payload))
    sock.sendall(header + payload)


def recv_cmd(sock: socket.socket) -> Tuple[bytes, bytes]:
    """Lit la prochaine commande et son payload.

    Retourne (cmd, payload).
    """
    header = _recv_exactly(sock, 12)
    cmd = header[:4]
    length = struct.unpack(">Q", header[4:])[0]
    payload = _recv_exactly(sock, length) if length > 0 else b""
    return cmd, payload


def _send_synthetic_file(sock: socket.socket, size: int) -> None:
    """Envoie un fichier synthétique (données nulles) de *size* octets.

    Format sur le fil :
        [CMD_FILE][8 = taille du champ size][size (8 octets)]
        puis *size* octets de données brutes.
    """
    send_cmd(sock, CMD_FILE, struct.pack(">Q", size))
    remaining = size
    blank = bytes(BUFFER_SIZE)
    while remaining > 0:
        to_send = min(BUFFER_SIZE, remaining)
        sock.sendall(blank[:to_send])
        remaining -= to_send


def _recv_file_data(sock: socket.socket, size: int) -> None:
    """Lit et ignore *size* octets de données de fichier depuis le socket."""
    remaining = size
    while remaining > 0:
        chunk = sock.recv(min(BUFFER_SIZE, remaining))
        if not chunk:
            raise ConnectionError("Connexion fermée pendant la réception du fichier.")
        remaining -= len(chunk)


# ---------------------------------------------------------------------------
# Résultat d'un test
# ---------------------------------------------------------------------------

@dataclass
class TestResult:
    direction: str       # "upload" ou "download"
    file_type: str       # "small", "medium" ou "large"
    file_count: int
    total_bytes: int
    elapsed_seconds: float
    ping_ms: float = 0.0
    repeat_index: int = 1

    @property
    def throughput_mbps(self) -> float:
        """Débit en Mbit/s (méga-bits par seconde, base 10)."""
        if self.elapsed_seconds <= 0:
            return 0.0
        return (self.total_bytes * 8) / (self.elapsed_seconds * 1_000_000)

    @property
    def throughput_mib_s(self) -> float:
        """Débit en Mio/s (mébi-octets par seconde, base 2)."""
        if self.elapsed_seconds <= 0:
            return 0.0
        return self.total_bytes / (self.elapsed_seconds * 1_048_576)

    def human_size(self) -> str:
        if self.total_bytes >= 1_048_576:
            return f"{self.total_bytes / 1_048_576:.1f} Mio"
        return f"{self.total_bytes / 1_024:.1f} Kio"

    def __str__(self) -> str:
        arrow = "→" if self.direction == "upload" else "←"
        ping_info = f"  [latence: {self.ping_ms:.1f} ms]" if self.ping_ms else ""
        return (
            f"  {arrow} [{self.direction.upper():8s}] {self.file_type:6s} : "
            f"{self.file_count} fichier(s), {self.human_size()} "
            f"en {self.elapsed_seconds:.3f} s  →  "
            f"{self.throughput_mib_s:.2f} Mio/s "
            f"({self.throughput_mbps:.1f} Mbps)"
            f"{ping_info}"
        )

    def to_dict(self) -> dict:
        return {
            "direction": self.direction,
            "file_type": self.file_type,
            "file_count": self.file_count,
            "total_bytes": self.total_bytes,
            "elapsed_seconds": round(self.elapsed_seconds, 6),
            "throughput_mbps": round(self.throughput_mbps, 3),
            "throughput_mib_s": round(self.throughput_mib_s, 3),
            "ping_ms": round(self.ping_ms, 3),
            "repeat_index": self.repeat_index,
        }


# ---------------------------------------------------------------------------
# Serveur
# ---------------------------------------------------------------------------

class Server:
    """Mode serveur : attend les connexions des clients et exécute les tests."""

    def __init__(self, host: str = "0.0.0.0", port: int = DEFAULT_PORT) -> None:
        self.host = host
        self.port = port

    def run(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((self.host, self.port))
            srv.listen(5)
            print(f"[SERVEUR] En écoute sur {self.host}:{self.port}  (Ctrl+C pour arrêter)")
            while True:
                conn, addr = srv.accept()
                print(f"[SERVEUR] Connexion entrante de {addr[0]}:{addr[1]}")
                t = threading.Thread(
                    target=self._handle_client,
                    args=(conn, addr),
                    daemon=True,
                )
                t.start()

    def _handle_client(self, conn: socket.socket, addr: Tuple[str, int]) -> None:
        try:
            with conn:
                while True:
                    try:
                        cmd, payload = recv_cmd(conn)
                    except (ConnectionError, OSError):
                        break

                    if cmd == CMD_BYE:
                        break
                    elif cmd == CMD_PING:
                        send_cmd(conn, CMD_PONG)
                    elif cmd == CMD_UPLOAD:
                        self._handle_upload(conn, payload.decode())
                    elif cmd == CMD_DOWNLOAD:
                        self._handle_download(conn, payload.decode())
                    else:
                        send_cmd(conn, CMD_ERROR, b"Commande inconnue")
        except Exception as exc:
            print(f"[SERVEUR] Erreur avec {addr[0]}: {exc}")
        finally:
            print(f"[SERVEUR] Déconnexion de {addr[0]}")

    # ------------------------------------------------------------------
    # Réception de fichiers (le client envoie, le serveur reçoit)
    # ------------------------------------------------------------------

    def _handle_upload(self, conn: socket.socket, test_type: str) -> None:
        """Reçoit des fichiers envoyés par le client et mesure le débit."""
        print(f"[SERVEUR] Test upload ({test_type}) en cours...")

        # Ping de départ
        cmd, _ = recv_cmd(conn)
        if cmd != CMD_PING:
            return
        send_cmd(conn, CMD_PONG)
        t_start = time.monotonic()

        # Réception des fichiers
        total_bytes = 0
        file_count = 0
        while True:
            cmd, payload = recv_cmd(conn)
            if cmd == CMD_DONE:
                break
            if cmd == CMD_FILE:
                size = struct.unpack(">Q", payload)[0]
                _recv_file_data(conn, size)
                total_bytes += size
                file_count += 1

        # Ping de fin
        cmd, _ = recv_cmd(conn)
        if cmd != CMD_PING:
            return
        send_cmd(conn, CMD_PONG)
        t_end = time.monotonic()

        elapsed = t_end - t_start
        result = TestResult("upload", test_type, file_count, total_bytes, elapsed)
        print(f"[SERVEUR]{result}")
        send_cmd(conn, CMD_RESULT, json.dumps(result.to_dict()).encode())

    # ------------------------------------------------------------------
    # Envoi de fichiers (le serveur envoie, le client reçoit)
    # ------------------------------------------------------------------

    def _handle_download(self, conn: socket.socket, test_type: str) -> None:
        """Envoie des fichiers au client pour mesurer le débit descendant."""
        print(f"[SERVEUR] Test download ({test_type}) en cours...")

        # Ping de départ (déclenché par le client)
        cmd, _ = recv_cmd(conn)
        if cmd != CMD_PING:
            return
        send_cmd(conn, CMD_PONG)

        # Envoi des fichiers
        if test_type == TEST_SMALL:
            for _ in range(SMALL_FILE_COUNT):
                _send_synthetic_file(conn, SMALL_FILE_SIZE)
        elif test_type == TEST_MEDIUM:
            _send_synthetic_file(conn, MEDIUM_FILE_SIZE)
        elif test_type == TEST_LARGE:
            _send_synthetic_file(conn, LARGE_FILE_SIZE)

        send_cmd(conn, CMD_DONE)

        # Ping de fin (déclenché par le client une fois CMD_DONE reçu)
        cmd, _ = recv_cmd(conn)
        if cmd != CMD_PING:
            return
        send_cmd(conn, CMD_PONG)


# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------

class Client:
    """Mode client : se connecte au serveur et exécute les tests."""

    def __init__(
        self, server_ip: str, port: int = DEFAULT_PORT, timeout: float = DEFAULT_TIMEOUT
    ) -> None:
        self.server_ip = server_ip
        self.port = port
        self.timeout = timeout

    def _connect(self) -> socket.socket:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(self.timeout)
        sock.connect((self.server_ip, self.port))
        return sock

    def measure_ping(self) -> float:
        """Mesure la latence aller-retour (RTT) en millisecondes via une
        connexion TCP dédiée.  Retourne -1.0 en cas d'échec."""
        try:
            with self._connect() as sock:
                t0 = time.monotonic()
                send_cmd(sock, CMD_PING)
                cmd, _ = recv_cmd(sock)
                rtt = (time.monotonic() - t0) * 1000
                send_cmd(sock, CMD_BYE)
                if cmd == CMD_PONG:
                    return rtt
        except OSError:
            pass
        return -1.0

    # ------------------------------------------------------------------
    # Envoi de fichiers (upload : client → serveur)
    # ------------------------------------------------------------------

    def run_upload(self, test_type: str, repeat_index: int = 1) -> Optional[TestResult]:
        """Envoie des fichiers de test au serveur et retourne les métriques."""
        try:
            with self._connect() as sock:
                send_cmd(sock, CMD_UPLOAD, test_type.encode())

                # Ping de départ → timestamp t_start
                ping_ms = self._tcp_ping(sock)
                t_start = time.monotonic()

                # Envoi des fichiers
                if test_type == TEST_SMALL:
                    for _ in range(SMALL_FILE_COUNT):
                        _send_synthetic_file(sock, SMALL_FILE_SIZE)
                    total_bytes = SMALL_FILE_SIZE * SMALL_FILE_COUNT
                    file_count = SMALL_FILE_COUNT
                elif test_type == TEST_MEDIUM:
                    _send_synthetic_file(sock, MEDIUM_FILE_SIZE)
                    total_bytes = MEDIUM_FILE_SIZE
                    file_count = 1
                elif test_type == TEST_LARGE:
                    _send_synthetic_file(sock, LARGE_FILE_SIZE)
                    total_bytes = LARGE_FILE_SIZE
                    file_count = 1
                else:
                    return None

                send_cmd(sock, CMD_DONE)

                # Ping de fin → timestamp t_end
                self._tcp_ping(sock)
                t_end = time.monotonic()

                elapsed = t_end - t_start
                result = TestResult(
                    "upload", test_type, file_count, total_bytes, elapsed, ping_ms, repeat_index
                )

                # Le serveur nous renvoie aussi ses métriques (ignoré ici,
                # on préfère la mesure côté client qui est plus précise).
                cmd, _ = recv_cmd(sock)
                send_cmd(sock, CMD_BYE)

                return result

        except Exception as exc:
            print(f"[CLIENT] Erreur upload ({test_type}): {exc}")
            return None

    # ------------------------------------------------------------------
    # Réception de fichiers (download : serveur → client)
    # ------------------------------------------------------------------

    def run_download(self, test_type: str, repeat_index: int = 1) -> Optional[TestResult]:
        """Reçoit des fichiers de test depuis le serveur et retourne les métriques."""
        try:
            with self._connect() as sock:
                send_cmd(sock, CMD_DOWNLOAD, test_type.encode())

                # Ping de départ → timestamp t_start
                ping_ms = self._tcp_ping(sock)
                t_start = time.monotonic()

                # Réception des fichiers
                total_bytes = 0
                file_count = 0
                while True:
                    cmd, payload = recv_cmd(sock)
                    if cmd == CMD_DONE:
                        break
                    if cmd == CMD_FILE:
                        size = struct.unpack(">Q", payload)[0]
                        _recv_file_data(sock, size)
                        total_bytes += size
                        file_count += 1

                # Ping de fin → timestamp t_end
                self._tcp_ping(sock)
                t_end = time.monotonic()

                elapsed = t_end - t_start
                result = TestResult(
                    "download", test_type, file_count, total_bytes, elapsed, ping_ms, repeat_index
                )
                send_cmd(sock, CMD_BYE)
                return result

        except Exception as exc:
            print(f"[CLIENT] Erreur download ({test_type}): {exc}")
            return None

    # ------------------------------------------------------------------
    # Helpers internes
    # ------------------------------------------------------------------

    def _tcp_ping(self, sock: socket.socket) -> float:
        """Envoie un PING sur *sock* et retourne le RTT en ms."""
        t0 = time.monotonic()
        send_cmd(sock, CMD_PING)
        cmd, _ = recv_cmd(sock)
        if cmd != CMD_PONG:
            raise ProtocolError(f"Attendu CMD_PONG, reçu {cmd!r}")
        return (time.monotonic() - t0) * 1000


# ---------------------------------------------------------------------------
# Fonctions utilitaires d'affichage
# ---------------------------------------------------------------------------

def _separator(char: str = "─", width: int = 72) -> None:
    print(char * width)


def _print_summary(results: List[TestResult], target: str) -> None:
    if not results:
        print("  Aucun résultat disponible.")
        return
    _separator()
    print(f"\n  RÉSUMÉ — {target}\n")
    _separator("-")
    for r in results:
        print(str(r))
    _separator("-")
    uploads = [r for r in results if r.direction == "upload"]
    downloads = [r for r in results if r.direction == "download"]
    if uploads:
        avg = sum(r.throughput_mbps for r in uploads) / len(uploads)
        print(f"  Débit moyen  upload   : {avg:.1f} Mbps")
    if downloads:
        avg = sum(r.throughput_mbps for r in downloads) / len(downloads)
        print(f"  Débit moyen  download : {avg:.1f} Mbps")

    if len({r.repeat_index for r in results}) > 1:
        _separator("-")
        print("  Agrégats par test (min / moy / max) :")
        groups = {}
        for r in results:
            key = (r.direction, r.file_type)
            groups.setdefault(key, []).append(r.throughput_mbps)

        for (direction, file_type) in sorted(groups.keys()):
            values = groups[(direction, file_type)]
            min_v = min(values)
            avg_v = sum(values) / len(values)
            max_v = max(values)
            print(
                f"  {direction:8s} {file_type:6s} : "
                f"{min_v:.1f} / {avg_v:.1f} / {max_v:.1f} Mbps"
            )

    _separator("═")
    print()


# ---------------------------------------------------------------------------
# Orchestration des tests
# ---------------------------------------------------------------------------

def run_tests(
    client: Client, tests: List[str], direction: str, repeat_count: int
) -> List[TestResult]:
    """Lance les tests demandés et affiche chaque résultat au fur et à mesure."""
    results: List[TestResult] = []
    for repeat_index in range(1, repeat_count + 1):
        if repeat_count > 1:
            print(f"\n  --- Passage {repeat_index}/{repeat_count} ---")
        for test_type in tests:
            if direction in ("upload", "both"):
                print(f"  → Envoi    ({test_type})…")
                r = client.run_upload(test_type, repeat_index=repeat_index)
                if r:
                    results.append(r)
                    print(str(r))
            if direction in ("download", "both"):
                print(f"  ← Réception ({test_type})…")
                r = client.run_download(test_type, repeat_index=repeat_index)
                if r:
                    results.append(r)
                    print(str(r))
    return results


def _results_with_target(results: List[TestResult], target: str) -> List[dict]:
    rows = []
    for r in results:
        row = r.to_dict()
        row["target"] = target
        rows.append(row)
    return rows


def _write_results(path: str, rows: List[dict], metadata: dict) -> None:
    if not rows:
        print("[INFO] Aucun résultat à exporter.")
        return

    ext = os.path.splitext(path)[1].lower()
    if ext == ".json":
        payload = {
            "metadata": metadata,
            "results": rows,
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
    elif ext == ".csv":
        fieldnames = [
            "target",
            "direction",
            "file_type",
            "repeat_index",
            "file_count",
            "total_bytes",
            "elapsed_seconds",
            "throughput_mbps",
            "throughput_mib_s",
            "ping_ms",
        ]
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
    else:
        raise ValueError("Format de sortie non supporté. Utilisez .json ou .csv")


# ---------------------------------------------------------------------------
# Lecture de la configuration
# ---------------------------------------------------------------------------

def _load_config(path: str) -> configparser.ConfigParser:
    cfg = configparser.ConfigParser()
    read = cfg.read(path)
    if not read:
        sys.exit(f"[ERREUR] Impossible de lire le fichier de configuration : {path}")
    return cfg


def _parse_test_types(value: str) -> List[str]:
    if value.strip().lower() == "all":
        return list(ALL_TESTS)
    result = []
    for t in value.split(","):
        t = t.strip().lower()
        if t in ALL_TESTS:
            result.append(t)
    return result or list(ALL_TESTS)


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("Doit être un entier strictement positif.")
    return parsed


def _positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("Doit être un nombre strictement positif.")
    return parsed


# ---------------------------------------------------------------------------
# Point d'entrée principal
# ---------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="speedtest.py",
        description="speed-intranet — Mesure de la bande passante d'un réseau local",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""
Exemples d'utilisation :

  # Sur chaque machine secondaire (terminal annexe) :
  python speedtest.py server

  # Sur la machine principale, tester un terminal :
  python speedtest.py client --server 192.168.1.2

  # Sur la machine principale, tester tous les terminaux listés dans config.ini :
  python speedtest.py auto --config config.ini

Options de tests (--tests) :
  small            200 fichiers de 1 Kio
  medium           1 fichier de 20 Mio
  large            1 fichier de 300 Mio
  all              Tous les tests ci-dessus (défaut)
  small,medium     Combinaison possible

Direction (--direction) :
  upload           Émission uniquement (terminal 1 → terminal annexe)
  download         Réception uniquement (terminal annexe → terminal 1)
  both             Les deux sens (défaut)
""",
    )

    parser.add_argument(
        "mode",
        choices=["server", "client", "auto"],
        help="Mode d'exécution : server | client | auto",
    )
    parser.add_argument(
        "--version",
        action="version",
        version=f"speed-intranet v{VERSION}",
        help="Afficher la version puis quitter",
    )
    parser.add_argument(
        "--server",
        metavar="IP",
        help="Adresse IP du serveur cible (obligatoire en mode client)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"Port TCP (défaut : {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--config",
        default="config.ini",
        help="Fichier de configuration INI (mode auto, défaut : config.ini)",
    )
    parser.add_argument(
        "--tests",
        default="all",
        metavar="TYPES",
        help="Types de tests : small, medium, large, all (défaut : all)",
    )
    parser.add_argument(
        "--direction",
        choices=["upload", "download", "both"],
        default="both",
        help="Direction des tests (défaut : both)",
    )
    parser.add_argument(
        "--repeat",
        type=_positive_int,
        default=1,
        help="Nombre de passages complets de la batterie de tests (défaut : 1)",
    )
    parser.add_argument(
        "--timeout",
        type=_positive_float,
        default=DEFAULT_TIMEOUT,
        help=f"Timeout socket en secondes (défaut : {DEFAULT_TIMEOUT})",
    )
    parser.add_argument(
        "--output",
        metavar="FILE",
        help="Exporter les résultats vers un fichier .json ou .csv",
    )
    return parser


def main() -> None:
    parser = _build_parser()
    args = parser.parse_args()

    _separator("═")
    print(f"  speed-intranet v{VERSION} — Mesure de bande passante réseau local")
    _separator("═")

    # ------------------------------------------------------------------ server
    if args.mode == "server":
        server = Server(port=args.port)
        server.run()

    # ------------------------------------------------------------------ client
    elif args.mode == "client":
        if not args.server:
            parser.error("--server <IP> est requis en mode client.")

        tests = _parse_test_types(args.tests)
        client = Client(args.server, args.port, timeout=args.timeout)

        print(f"\n  Serveur cible : {args.server}:{args.port}")
        print(f"  Tests         : {', '.join(tests)}")
        print(f"  Direction     : {args.direction}")
        print(f"  Répétitions   : {args.repeat}")
        print(f"  Timeout       : {args.timeout:.1f} s")

        # Mesure de la latence initiale
        ping = client.measure_ping()
        if ping >= 0:
            print(f"  Latence TCP   : {ping:.1f} ms\n")
        else:
            print("  Latence TCP   : indisponible\n")
        _separator()

        results = run_tests(client, tests, args.direction, args.repeat)
        _print_summary(results, args.server)

        if args.output:
            rows = _results_with_target(results, args.server)
            metadata = {
                "version": VERSION,
                "mode": "client",
                "server": args.server,
                "port": args.port,
                "tests": tests,
                "direction": args.direction,
                "repeat": args.repeat,
                "timeout_seconds": args.timeout,
            }
            _write_results(args.output, rows, metadata)
            print(f"[INFO] Résultats exportés dans : {args.output}")

    # ------------------------------------------------------------------ auto
    elif args.mode == "auto":
        cfg = _load_config(args.config)

        if not cfg.has_section("network"):
            sys.exit(f"[ERREUR] Section [network] absente dans {args.config}")

        port = cfg.getint("network", "port", fallback=DEFAULT_PORT)
        terminals_raw = cfg.get("network", "terminals", fallback="")
        terminals = [ip.strip() for ip in terminals_raw.split(",") if ip.strip()]

        if not terminals:
            sys.exit("[ERREUR] Aucune adresse IP de terminal trouvée dans la config.")

        tests_cfg = cfg.get("tests", "test_types", fallback="all")
        tests = _parse_test_types(tests_cfg)
        direction = cfg.get("tests", "direction", fallback="both")
        repeat_count = cfg.getint("tests", "repeat", fallback=args.repeat)
        timeout = cfg.getfloat("network", "timeout", fallback=args.timeout)

        if direction not in ("upload", "download", "both"):
            sys.exit("[ERREUR] La direction doit être upload, download ou both.")
        if repeat_count <= 0:
            sys.exit("[ERREUR] repeat doit être strictement positif.")
        if timeout <= 0:
            sys.exit("[ERREUR] timeout doit être strictement positif.")

        print(f"\n  Configuration : {args.config}")
        print(f"  Terminaux     : {', '.join(terminals)}")
        print(f"  Port          : {port}")
        print(f"  Tests         : {', '.join(tests)}")
        print(f"  Direction     : {direction}")
        print(f"  Répétitions   : {repeat_count}")
        print(f"  Timeout       : {timeout:.1f} s\n")

        all_rows: List[dict] = []

        for terminal_ip in terminals:
            _separator()
            print(f"\n  === Test avec le terminal {terminal_ip} ===\n")
            client = Client(terminal_ip, port, timeout=timeout)

            ping = client.measure_ping()
            if ping >= 0:
                print(f"  Latence TCP : {ping:.1f} ms\n")
            else:
                print(f"  [AVERTISSEMENT] Impossible de joindre {terminal_ip}:{port}\n")
                continue

            results = run_tests(client, tests, direction, repeat_count)
            _print_summary(results, terminal_ip)
            all_rows.extend(_results_with_target(results, terminal_ip))

        if args.output:
            metadata = {
                "version": VERSION,
                "mode": "auto",
                "port": port,
                "terminals": terminals,
                "tests": tests,
                "direction": direction,
                "repeat": repeat_count,
                "timeout_seconds": timeout,
            }
            _write_results(args.output, all_rows, metadata)
            print(f"[INFO] Résultats exportés dans : {args.output}")


if __name__ == "__main__":
    main()
