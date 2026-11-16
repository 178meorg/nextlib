"""Generate deterministic bytes for SMB integration tests; this is not a playable video."""
import argparse
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root', default='/tmp/nextlib-smb-share')
args = parser.parse_args()
folder = Path(args.root) / '目录 #100%'
folder.mkdir(parents=True, exist_ok=True)
(folder / '视频 #100%.mp4').write_bytes(bytes(i % 251 for i in range(262161)))
