#!/usr/bin/env python3
"""Reproduce the gated RealMedia experiments; generated artifacts stay in root build/realmedia.

Host: python3 media3ext/src/test/cpp/run_realmedia_probe.py
Android: JAVA_HOME=... ANDROID_SERIAL=... python3 .../run_realmedia_probe.py --android
This does not publish an AAR or claim playback acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import time
import tomllib
import urllib.request
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[4]
TESTS = Path(__file__).resolve().parent
BUILD = ROOT / 'build/realmedia'
MANIFEST = json.loads((TESTS / 'realmedia_samples.json').read_text())


def run(args, log, cwd=ROOT, env=None):
    print('Running:', ' '.join(str(a) for a in args), flush=True)
    with log.open('w') as output:
        result = subprocess.run([str(a) for a in args], cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}); see {log}')


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def fetch(url, destination, expected):
    if destination.exists() and sha256(destination) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = destination.with_suffix(destination.suffix + '.download')
    try:
        print('Downloading:', url, flush=True)
        with urllib.request.urlopen(url, timeout=60) as source, staging.open('wb') as output:
            shutil.copyfileobj(source, output)
        if sha256(staging) != expected:
            raise RuntimeError(f'SHA-256 mismatch for {url}; existing destination was preserved')
        staging.replace(destination)
    finally:
        staging.unlink(missing_ok=True)


def host(args):
    source_info = MANIFEST['ffmpeg']
    tar = BUILD / 'ffmpeg.tar.gz'
    fetch(source_info['url'], tar, source_info['sha256'])
    source = BUILD / ('ffmpeg-' + source_info['version'])
    if not source.exists():
        with tarfile.open(tar) as archive:
            archive.extractall(BUILD, filter='data')
    host_build = BUILD / 'host'
    host_build.mkdir(exist_ok=True)
    options = [source / 'configure', '--disable-everything', '--disable-doc', '--disable-programs',
               '--disable-network', '--disable-autodetect', '--disable-x86asm', '--disable-avdevice',
               '--disable-avfilter', '--disable-shared', '--enable-static', '--enable-pic',
               '--enable-avformat', '--enable-swresample', '--enable-swscale', '--enable-demuxer=rm',
               '--enable-decoder=rv30,rv40,cook,aac', '--enable-parser=cook,aac', '--enable-protocol=file']
    stamp = host_build / 'probe-configure.json'
    signature = json.dumps([str(x) for x in options])
    if not stamp.exists() or stamp.read_text() != signature:
        run(options, host_build / 'configure.log', cwd=host_build)
        stamp.write_text(signature)
    run(['make', f'-j{args.jobs}'], host_build / 'make.log', cwd=host_build)
    executable = BUILD / 'reference_test'
    run([os.environ.get('CXX', 'c++'), '-std=c++17', '-O2', '-I' + str(source), '-I' + str(host_build),
         TESTS / 'realmedia_reference_test.cpp', '-L' + str(host_build / 'libavformat'),
         '-L' + str(host_build / 'libavcodec'), '-L' + str(host_build / 'libavutil'),
         '-lavformat', '-lavcodec', '-lavutil', '-lm', '-lpthread', '-o', executable],
        BUILD / 'host-compile.log')
    report = {'baseline_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'ffmpeg_version': source_info['version'], 'ffmpeg_sha256': source_info['sha256'],
              'probe_sha256': sha256(TESTS / 'realmedia_reference_test.cpp'), 'samples': {}}
    for entry in MANIFEST['samples']:
        if entry['role'] != 'reference' and not args.include_negative:
            continue
        sample = BUILD / 'samples' / entry['name']
        fetch(entry['url'], sample, entry['sha256'])
        result = subprocess.run([executable, sample], capture_output=True, text=True)
        (BUILD / (entry['name'] + '.stderr.log')).write_text(result.stderr)
        expected_success = entry['role'] == 'reference'
        report['samples'][entry['name']] = {'sha256': entry['sha256'], 'role': entry['role'],
                                'exit_code': result.returncode}
        if result.returncode == 0:
            report['samples'][entry['name']]['result'] = json.loads(result.stdout)
        else:
            report['samples'][entry['name']]['diagnostic'] = result.stderr[-2000:]
        (BUILD / 'host-results.json').write_text(json.dumps(report, indent=2) + '\n')
        if (result.returncode == 0) != expected_success:
            raise RuntimeError(f"Unexpected result for {entry['name']}; see host-results.json")
        print(entry['name'], 'reference passed' if expected_success else 'negative case rejected', flush=True)
    if args.local_sample:
        sample = Path(args.local_sample).resolve(strict=True)
        result = subprocess.run([executable, sample], capture_output=True, text=True)
        record = {'name': sample.name, 'sha256': sha256(sample), 'bytes': sample.stat().st_size,
                  'exit_code': result.returncode}
        (BUILD / 'user-sample.stderr.log').write_text(result.stderr)
        if result.returncode == 0:
            record['result'] = json.loads(result.stdout)
        else:
            record['diagnostic'] = result.stderr[-2000:]
        (BUILD / 'user-sample-results.json').write_text(json.dumps(record, indent=2, ensure_ascii=False) + '\n')
        if result.returncode:
            raise RuntimeError('Local sample failed the reference probe; see user-sample-results.json')


def android(args):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        properties = ROOT / 'local.properties'
        for line in properties.read_text().splitlines() if properties.exists() else []:
            if line.startswith('sdk.dir='):
                sdk = line.split('=', 1)[1]
    if not sdk:
        raise RuntimeError('Set ANDROID_HOME or sdk.dir in local.properties')
    sdk = Path(sdk)
    versions = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())['versions']
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', sdk / 'ndk' / versions['ndk']))
    serial = args.serial or os.environ.get('ANDROID_SERIAL')
    if not serial:
        raise RuntimeError('Pass --serial or set ANDROID_SERIAL to a disposable device/emulator')
    env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL=serial,
               NEXTLIB_BUILD_JOBS=str(args.jobs))
    inputs = [TESTS / 'realmedia_probe_jni.cpp', TESTS / 'realmedia_reference_test.cpp', TESTS / 'realmedia_probe.exports',
              ROOT / 'media3ext/src/main/cpp/ffaudio.cpp', ROOT / 'media3ext/src/main/cpp/ffvideo.cpp']
    inputs += sorted((ROOT / 'media3ext/src/realmediaProbe').rglob('*.java'))
    inputs += sorted((ROOT / 'media3ext/src/main/java').rglob('*.java'))
    inputs += sorted((ROOT / 'media3ext/src/main/cpp').glob('*'))
    inputs += [ROOT / 'gradle.properties', ROOT / 'gradle/libs.versions.toml', ROOT / 'ffmpeg/setup.sh']
    inputs = [path for path in inputs if path.is_file()]
    source_hashes = {str(path.relative_to(ROOT)): sha256(path) for path in inputs}
    adb = sdk / 'platform-tools/adb'
    run([ROOT / 'gradlew', ':ffmpegSetup'], BUILD / 'android-ffmpeg.log', env=env)
    tool_host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    targets = {'x86': 'i686-linux-android21', 'x86_64': 'x86_64-linux-android21',
               'armeabi-v7a': 'armv7a-linux-androideabi21', 'arm64-v8a': 'aarch64-linux-android21'}
    for abi, target in targets.items():
        directory = BUILD / 'android-jni' / abi
        directory.mkdir(parents=True, exist_ok=True)
        compiler = ndk / 'toolchains/llvm/prebuilt' / tool_host / 'bin' / (target + '-clang++')
        libs = ROOT / 'ffmpeg/output/lib' / abi
        run([compiler, '-std=c++17', '-O2', '-fPIC', '-shared', '-static-libstdc++',
             '-Wl,--version-script=' + str(TESTS / 'realmedia_probe.exports'),
             '-Wl,-z,max-page-size=16384', '-I' + str(ROOT / 'ffmpeg/output/include' / abi),
             TESTS / 'realmedia_probe_jni.cpp', ROOT / 'media3ext/src/main/cpp/ffcommon.cpp',
             '-L' + str(libs), '-lavformat', '-lavcodec', '-lavutil', '-lswresample', '-lswscale',
             '-landroid', '-llog', '-o', directory / 'librealmedia_probe.so'],
            BUILD / (abi + '-probe-compile.log'))
        shutil.copy2(libs / 'libavformat.so', directory)
    assets = BUILD / 'android-assets/realmedia'
    assets.mkdir(parents=True, exist_ok=True)
    # Disposable loopback-only TLS identity. These generated files are never part of an AAR.
    tls = BUILD / 'test-tls'
    tls.mkdir(exist_ok=True)
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
         '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
         '-keyout', tls / 'key.pem', '-out', tls / 'cert.pem'], BUILD / 'test-tls-cert.log')
    run(['openssl', 'pkcs12', '-export', '-inkey', tls / 'key.pem', '-in', tls / 'cert.pem',
         '-name', 'nextlib-loopback-test', '-passout', 'pass:nextlib-test-only',
         '-out', assets / 'test-tls.p12'], BUILD / 'test-tls-p12.log')
    for entry in MANIFEST['samples']:
        if entry['role'] == 'reference':
            shutil.copy2(BUILD / 'samples' / entry['name'], assets)
    for entry in MANIFEST['regressions']:
        source = BUILD / 'samples' / entry['name']
        fetch(entry['url'], source, entry['sha256'])
        shutil.copy2(source, assets)
    user_asset = assets / 'user-sample.rmvb'
    if args.local_sample:
        shutil.copy2(Path(args.local_sample), user_asset)
    else:
        user_asset.unlink(missing_ok=True)
    test_arguments = ['-Pandroid.testInstrumentationRunnerArguments.realmediaUserSample=true'] if args.local_sample else []
    if args.long_playback:
        test_arguments.append('-Pandroid.testInstrumentationRunnerArguments.realmediaLongPlayback=true')
    device = {}
    for key in ('ro.product.cpu.abi', 'ro.build.version.sdk', 'ro.build.fingerprint'):
        device[key] = subprocess.check_output([str(adb), '-s', serial, 'shell', 'getprop', key], text=True).strip()
    classes = {
        'prototype': 'realmedia.RealMediaPrototypeTest',
        'playback': 'realmedia.RealMediaPlaybackTest',
        'renderer': 'ffdecoder.FfmpegVideoRendererTest',
        'lifecycle': 'ffdecoder.DecoderManagerLifecycleTest',
    }
    # AGP's connected test class filter can silently select only the first comma-separated
    # class. Invoke each class separately and require its own non-empty result set.
    tests = []
    failed = False
    for label in args.test_class or classes:
        started = time.time()
        with (BUILD / ('android-' + label + '-results.log')).open('w') as output:
            result = subprocess.run([str(ROOT / 'gradlew'), ':media3ext:connectedDebugAndroidTest',
                                    '-PnextlibRealMediaProbe=true',
                                    '-Pandroid.testInstrumentationRunnerArguments.class='
                                    'io.github.anilbeesetti.nextlib.media3ext.' + classes[label], *test_arguments],
                                   cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        current = []
        for file in (ROOT / 'media3ext/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml'):
            if file.stat().st_mtime < started:
                continue
            for test in ET.parse(file).getroot().iter('testcase'):
                failure = test.find('failure')
                skipped = test.find('skipped') is not None or (failure is not None and
                    'org.junit.AssumptionViolatedException' in (failure.text or ''))
                current.append({'class': test.attrib.get('classname'), 'name': test.attrib['name'],
                                'passed': failure is None and not skipped, 'skipped': skipped,
                                'failure': None if failure is None or skipped else failure.text})
        changed = any(sha256(ROOT / name) != digest for name, digest in source_hashes.items())
        summary = {'device': device, 'source_sha256': source_hashes,
                   'sources_changed_during_run': changed, 'tests': current}
        (BUILD / ('android-test-summary-' + label + '.json')).write_text(json.dumps(summary, indent=2) + '\n')
        tests.extend(current)
        failed |= result.returncode != 0 or not current or changed
        print(f"Android {label}: {sum(t['passed'] for t in current)} passed, "
              f"{sum(t['skipped'] for t in current)} skipped", flush=True)
    logs = subprocess.check_output([str(adb), '-s', serial, 'logcat', '-d', '-s', 'NextLibRealMediaProbe:I'], text=True)
    (BUILD / 'android-probe.log').write_text(logs)
    sources_changed = any(sha256(ROOT / name) != digest for name, digest in source_hashes.items())
    (BUILD / 'android-test-summary.json').write_text(json.dumps(
        {'device': device, 'source_sha256': source_hashes, 'sources_changed_during_run': sources_changed,
         'tests': tests}, indent=2) + '\n')
    if failed or any(not (t['passed'] or t['skipped']) for t in tests):
        raise RuntimeError('Android probe failed; see android-test-summary.json and android-*-results.log')
    print(f"Android probe: {sum(t['passed'] for t in tests)} tests passed, "
          f"{sum(t['skipped'] for t in tests)} skipped. This is not playback acceptance.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', action='store_true')
    parser.add_argument('--android-only', action='store_true', help='Reuse downloaded fixtures and run only Android checks')
    parser.add_argument('--test-class', action='append', choices=['prototype', 'playback', 'renderer', 'lifecycle'])
    parser.add_argument('--serial')
    parser.add_argument('--include-negative', action='store_true')
    parser.add_argument('--local-sample', help='Optional private sample, used locally and never added to the manifest')
    parser.add_argument('--long-playback', action='store_true', help='Play the entire private sample on Android at 8x')
    parser.add_argument('--jobs', type=int, default=min(os.cpu_count() or 4, 8))
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    if args.long_playback and (not args.local_sample or not (args.android or args.android_only)):
        parser.error('--long-playback requires --android and --local-sample')
    BUILD.mkdir(parents=True, exist_ok=True)
    if not args.android_only:
        host(args)
    if args.android or args.android_only:
        android(args)


if __name__ == '__main__':
    main()
