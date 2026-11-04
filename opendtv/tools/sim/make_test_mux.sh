#!/usr/bin/env bash
# Generates a test multiplex for the simulated tuner (debug builds, no dongle needed).
#
#   make_test_mux.sh OUT.ts TSID NETWORK_ID PROVIDER "sid|name|video|tone_hz" ...
#
# Each service gets an ffmpeg test pattern (smptebars, testsrc, testsrc2, rgbtestsrc, pal75bars, ...) with H.264
# video and an AAC tone, in a 20 s, 3 Mbit/s CBR transport stream with PAT / PMT / SDT / NIT. Name the file after
# the frequency in kHz and copy it into the app's sim folder, e.g. for UHF 21:
#
#   make_test_mux.sh 515143.ts 0x0065 0x0001 "Solar" "1|SolarFlix|testsrc|990" "3|DepEd TV|smptehdbars|1100"
#   adb push 515143.ts /data/local/tmp/
#   adb shell run-as ph.opendtv sh -c 'mkdir -p files/sim && cp /data/local/tmp/515143.ts files/sim/'
#
# Then run Auto Scan. On an emulator turn on "Hardware decoder" in the menu: the FFmpeg decoder shows these
# test streams garbled there. Needs ffmpeg with libx264.
set -euo pipefail
[ $# -ge 5 ] || { sed -n '2,18p' "$0"; exit 1; }
out=$1; tsid=$2; onid=$3; provider=$4; shift 4
args=(); maps=(); progs=(); pmeta=(); i=0
for spec in "$@"; do
  IFS='|' read -r sid name vsrc tone <<< "$spec"
  args+=(-f lavfi -i "${vsrc}=size=640x360:rate=25:duration=20" -f lavfi -i "sine=frequency=${tone}:sample_rate=48000:duration=20")
  v=$((i * 2)); a=$((i * 2 + 1))
  maps+=(-map "${v}:v" -map "${a}:a")
  progs+=(-program "program_num=${sid}:title=${name}:st=${v}:st=${a}")
  pmeta+=("-metadata:p:${i}" "service_provider=${provider}")
  i=$((i + 1))
done
ffmpeg -hide_banner -loglevel error -y "${args[@]}" "${maps[@]}" \
  -c:v libx264 -profile:v baseline -preset veryfast -g 25 -b:v 600k -maxrate 600k -bufsize 1200k -pix_fmt yuv420p \
  -c:a aac -b:a 64k -ac 2 \
  "${progs[@]}" "${pmeta[@]}" \
  -mpegts_transport_stream_id "$tsid" -mpegts_original_network_id "$onid" -mpegts_flags +nit+system_b \
  -muxrate 3000000 -f mpegts "$out"
echo "wrote $out"
