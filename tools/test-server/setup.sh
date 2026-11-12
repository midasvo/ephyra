#!/usr/bin/env bash
# Ephyra test Jellyfin server: generated media with edge cases, set up through the API.
#
#   tools/test-server/setup.sh            start or repair (safe to run again; it skips what exists)
#   tools/test-server/setup.sh stop       stop the container, keep everything
#   tools/test-server/setup.sh status     is it up, and what is on it
#   tools/test-server/setup.sh wipe       remove the container and the server's state; keeps the
#                                         media and creds.env, so the next run is quick
#   tools/test-server/setup.sh wipe --media   also remove the generated media
#
# Needs Docker and python3 on the machine that runs it, nothing else: the media is made with the
# ffmpeg inside the Jellyfin image. On Windows, run it inside WSL. docs/dev-environment.md has
# the whole story.
#
# Settings (environment):
#   EPHYRA_TEST_SERVER_DIR    where config, cache, media and creds.env live
#                             (default ~/ephyra-test-server; about 1.5 GB; keep it out of the repo)
#   EPHYRA_TEST_SERVER_PORT   host port (default 8096)
#   EPHYRA_TEST_SERVER_NAME   container name (default ephyra-test-jellyfin)
#   EPHYRA_TEST_SERVER_IMAGE  Jellyfin image (default below: a fixed version, so the server
#                             behaves the same for everyone; raise it on purpose)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${EPHYRA_TEST_SERVER_DIR:-$HOME/ephyra-test-server}"
PORT="${EPHYRA_TEST_SERVER_PORT:-8096}"
NAME="${EPHYRA_TEST_SERVER_NAME:-ephyra-test-jellyfin}"
IMAGE="${EPHYRA_TEST_SERVER_IMAGE:-jellyfin/jellyfin:12.4}"
CREDS="$ROOT/creds.env"
export EPHYRA_TEST_SERVER_DIR="$ROOT"

command -v docker >/dev/null || { echo "docker not found (on Windows: run this inside WSL)" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

case "${1:-up}" in
  up) ;;
  stop) docker stop "$NAME" >/dev/null && echo "stopped $NAME"; exit 0 ;;
  status)
    docker ps -a --filter "name=^$NAME\$" --format '{{.Names}}: {{.Status}} ({{.Image}})'
    [ -f "$CREDS" ] && python3 "$HERE/configure.py" --report-only
    exit 0 ;;
  wipe)
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    # the container writes these folders as its own user, so remove them from inside a container
    docker run --rm --entrypoint sh -v "$ROOT:/root-dir" "$IMAGE" -c \
      "rm -rf /root-dir/config /root-dir/cache; [ '${2:-}' = --media ] && rm -rf /root-dir/media; true"
    echo "wiped $NAME in $ROOT (creds.env kept)"; exit 0 ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac

mkdir -p "$ROOT"/{config,cache,media/movies,media/shows}

# --- credentials: generated once, kept in creds.env, never printed -------------------------
if [ ! -f "$CREDS" ]; then
  umask 077
  pw() { python3 -c "import secrets; print(secrets.token_hex($1))"; }
  {
    echo "JF_URL_HOST=http://localhost:$PORT"
    echo "JF_URL_EMULATOR=http://10.0.2.2:$PORT"
    echo "JF_ADMIN_USER=admin"
    echo "JF_ADMIN_PASS=$(pw 8)"
    echo "JF_TESTER_USER=tester"      # may download
    echo "JF_TESTER_PASS=$(pw 6)"
    echo "JF_NODL_USER=nodownload"    # may not download
    echo "JF_NODL_PASS=$(pw 6)"
    echo "JF_SECOND_USER=second"      # second profile, may download
    echo "JF_SECOND_PASS=$(pw 6)"
    echo "JF_NOPASS_USER=nopass"      # empty password
  } > "$CREDS"
  umask 022
fi
# shellcheck disable=SC1090
source "$CREDS"

# --- media ---------------------------------------------------------------------------------
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull -q "$IMAGE" >/dev/null
if [ ! -f "$ROOT/media/.generated" ]; then
  docker run --rm --entrypoint bash -v "$ROOT/media:/media" "$IMAGE" -c '
    set -e
    FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
    clip() { # clip <out> <seconds> <freq> [extra video args]
      out="$1"; dur="$2"; freq="$3"; shift 3
      mkdir -p "$(dirname "$out")"
      $FF -v error -y -f lavfi -i "testsrc2=size=640x360:rate=24:duration=$dur" \
          -f lavfi -i "sine=frequency=$freq:duration=$dur" \
          -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 "$@" -c:a aac -b:a 96k -shortest "$out"
    }
    srt() { # srt <file> <label> <seconds>
      : > "$1"; n=1
      for s in $(seq 0 5 $(( $3 - 5 ))); do
        printf "%d\n00:%02d:%02d,000 --> 00:%02d:%02d,000\n%s line %d\n\n" \
          $n $((s/60)) $((s%60)) $(((s+4)/60)) $(((s+4)%60)) "$2" $n >> "$1"; n=$((n+1))
      done
    }
    M=/media/movies; S=/media/shows

    clip "$M/Plain Movie (2020)/Plain Movie (2020).mkv" 300 440
    clip "$M/Short Movie (2021)/Short Movie (2021).mp4" 20 550
    clip "$M/A Movie With A Really Long Title That Goes On And On To Test How Rows And Pages Wrap It (2019)/A Movie With A Really Long Title That Goes On And On To Test How Rows And Pages Wrap It (2019).mkv" 60 330

    # two audio tracks, two embedded subtitles, one external subtitle
    d="$M/Tracks Movie (2022)"; mkdir -p "$d"
    srt /tmp/en.srt English 120; srt /tmp/nl.srt Nederlands 120; srt "$d/Tracks Movie (2022).de.srt" Deutsch 120
    $FF -v error -y -f lavfi -i "testsrc2=size=640x360:rate=24:duration=120" \
        -f lavfi -i "sine=frequency=440:duration=120" -f lavfi -i "sine=frequency=880:duration=120" \
        -i /tmp/en.srt -i /tmp/nl.srt -map 0:v -map 1:a -map 2:a -map 3 -map 4 \
        -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 -c:a:0 aac -c:a:1 ac3 -ac:a:1 6 -c:s srt \
        -metadata:s:a:0 language=eng -metadata:s:a:0 title="English Stereo" \
        -metadata:s:a:1 language=dut -metadata:s:a:1 title="Dutch 5.1" \
        -metadata:s:s:0 language=eng -metadata:s:s:1 language=dut \
        -disposition:s:1 forced "$d/Tracks Movie (2022).mkv"

    # HEVC 10-bit
    clip "$M/Hevc Movie (2023)/Hevc Movie (2023).mkv" 60 660 -c:v libx265 -pix_fmt yuv420p10le -x265-params log-level=error

    # two versions of one movie
    clip "$M/Versions Movie (2018)/Versions Movie (2018) - 360p.mkv" 60 440
    clip "$M/Versions Movie (2018)/Versions Movie (2018) - 720p.mkv" 60 440 -vf scale=1280:720

    # a movie file that is not a video at all (fails to open)
    mkdir -p "$M/Broken Movie (2017)"; head -c 200000 /dev/urandom > "$M/Broken Movie (2017)/Broken Movie (2017).mkv"

    # shows
    for e in 01 02 03 04 05; do clip "$S/Test Show (2020)/Season 01/Test Show - S01E$e - Episode $e.mkv" 90 $((400 + 10#$e * 20)); done
    for e in 01 02; do clip "$S/Test Show (2020)/Season 02/Test Show - S02E$e - Episode $e.mkv" 90 700; done
    mkdir -p "$S/Test Show (2020)/Season 03"                                   # empty season
    clip "$S/Test Show (2020)/Specials/Test Show - S00E01 - Special.mkv" 30 300
    mkdir -p "$S/Empty Show (2021)"                                            # show without seasons
    mkdir -p "$S/Empty Season Show (2022)/Season 01"                           # show with one empty season
    clip "$S/One Episode Show (2023)/Season 01/One Episode Show - S01E01 - Only.mkv" 60 500
    for e in $(seq -w 1 30); do                                               # long season (cheap copies)
      [ "$e" = 01 ] && clip "$S/Long Season Show (2019)/Season 01/Long Season Show - S01E01.mkv" 30 450 \
        || cp "$S/Long Season Show (2019)/Season 01/Long Season Show - S01E01.mkv" "$S/Long Season Show (2019)/Season 01/Long Season Show - S01E$e.mkv"
    done
    chmod -R a+rX /media
  '
  touch "$ROOT/media/.generated"
fi

# --- real titles, test-pattern video: the server fetches posters, overviews and cast ---------
if [ ! -f "$ROOT/media/.generated-meta" ]; then
  docker run --rm --entrypoint bash -v "$ROOT/media:/media" "$IMAGE" -c '
    set -e
    FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
    M=/media/movies-meta; S=/media/shows-meta; mkdir -p "$M" "$S"
    $FF -v error -y -f lavfi -i "testsrc2=size=640x360:rate=24:duration=120" \
        -f lavfi -i "sine=frequency=440:duration=120" \
        -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 48 -c:a aac -b:a 64k -shortest /tmp/clip.mkv
    for t in "Night of the Living Dead (1968)" "Charade (1963)" "His Girl Friday (1940)" \
             "Sintel (2010)" "Big Buck Bunny (2008)" "Tears of Steel (2012)" \
             "The General (1926)" "Nosferatu (1922)" "Metropolis (1927)" "The Kid (1921)" \
             "The Matrix (1999)" "Inception (2010)" "Spirited Away (2001)" "Parasite (2019)"; do
      mkdir -p "$M/$t"; cp /tmp/clip.mkv "$M/$t/$t.mkv"
    done
    eps() { # eps <show folder> <season> <count>
      mkdir -p "$S/$1/Season $2"
      for e in $(seq -w 1 "$3"); do cp /tmp/clip.mkv "$S/$1/Season $2/$1 - S$2E$e.mkv"; done
    }
    eps "Pioneer One (2010)" 01 6
    eps "Breaking Bad (2008)" 01 7; eps "Breaking Bad (2008)" 02 13
    eps "The Office (2005)" 01 6; eps "The Office (2005)" 02 22
    eps "Bluey (2018)" 01 10
    eps "Chernobyl (2019)" 01 5
    chmod -R a+rX /media
  '
  touch "$ROOT/media/.generated-meta"
fi

# --- long items: Jellyfin keeps a resume position only for items of 5 minutes and more -------
if [ ! -f "$ROOT/media/.generated-long" ]; then
  docker run --rm --entrypoint bash -v "$ROOT/media:/media" "$IMAGE" -c '
    set -e
    FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
    $FF -v error -y -f lavfi -i "testsrc2=size=640x360:rate=24:duration=720" \
        -f lavfi -i "sine=frequency=440:duration=720" \
        -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 48 -c:a aac -b:a 64k -shortest /tmp/long.mkv
    mkdir -p "/media/movies/Long Movie (2016)"; cp /tmp/long.mkv "/media/movies/Long Movie (2016)/Long Movie (2016).mkv"
    d="/media/shows/Resume Show (2015)/Season 01"; mkdir -p "$d"
    for e in 01 02 03 04; do cp /tmp/long.mkv "$d/Resume Show - S01E$e - Part $e.mkv"; done
    chmod -R a+rX /media
  '
  touch "$ROOT/media/.generated-long"
fi

# --- HDR10 and an audio track without a language ---------------------------------------------
if [ ! -f "$ROOT/media/.generated-extra" ]; then
  docker run --rm --entrypoint bash -v "$ROOT/media:/media" "$IMAGE" -c '
    set -e
    FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
    src() { # src <seconds> <freq>: the two test inputs
      echo "-f lavfi -i testsrc2=size=640x360:rate=24:duration=$1 -f lavfi -i sine=frequency=$2:duration=$1"
    }
    d="/media/movies/Hdr Ten Movie (2024)"; mkdir -p "$d"                       # HEVC 10-bit, PQ, BT.2020
    # the colour tags have to be set in the filter: as plain output options they do not stick
    $FF -v error -y $(src 60 520) -c:v libx265 -g 48 \
        -vf "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc" \
        -x265-params "log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc" \
        -c:a aac -b:a 96k -shortest "$d/Hdr Ten Movie (2024).mkv"
    # audio tagged "und": it has to be .mp4, Matroska drops the tag
    d="/media/shows/Und Audio Show (2024)/Season 01"; mkdir -p "$d"
    $FF -v error -y $(src 30 480) -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 \
        -c:a aac -b:a 96k -metadata:s:a:0 language=und -shortest "$d/Und Audio Show - S01E01 - Undetermined.mp4"
    chmod -R a+rX /media
  '
  touch "$ROOT/media/.generated-extra"
fi

# --- a series with two audio tracks (the per-series track choice on a transcode, #580) -------
if [ ! -f "$ROOT/media/.generated-tracks-show" ]; then
  docker run --rm --entrypoint bash -v "$ROOT/media:/media" "$IMAGE" -c '
    set -e
    FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
    d="/media/shows/Tracks Show (2025)/Season 01"; mkdir -p "$d"
    for e in 01 02 03; do
      $FF -v error -y -f lavfi -i "testsrc2=size=640x360:rate=24:duration=90" \
          -f lavfi -i "sine=frequency=440:duration=90" -f lavfi -i "sine=frequency=880:duration=90" \
          -map 0:v -map 1:a -map 2:a -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 -c:a aac -b:a 96k \
          -metadata:s:a:0 language=eng -metadata:s:a:0 title="English" \
          -metadata:s:a:1 language=dut -metadata:s:a:1 title="Nederlands" \
          -disposition:a:0 default -disposition:a:1 0 "$d/Tracks Show - S01E$e - Episode $e.mkv"
    done
    chmod -R a+rX /media
  '
  touch "$ROOT/media/.generated-tracks-show"
fi

# --- container -----------------------------------------------------------------------------
if ! docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  docker run -d --name "$NAME" --restart unless-stopped -p "$PORT:8096" \
    -v "$ROOT/config:/config" -v "$ROOT/cache:/cache" -v "$ROOT/media:/media:ro" "$IMAGE" >/dev/null
else
  docker start "$NAME" >/dev/null
fi
python3 "$HERE/configure.py"
echo "credentials are in $CREDS (emulator address: $JF_URL_EMULATOR)"
