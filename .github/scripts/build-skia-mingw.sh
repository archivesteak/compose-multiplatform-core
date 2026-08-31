#!/usr/bin/env bash
set -euo pipefail

# Build the pinned Skia checkout with the GNU Windows ABI expected by Kotlin/Native mingwX64.
# The caller supplies both checkouts and the toolchain; this script never selects mutable inputs.
if [[ $# -ne 1 ]]; then
  echo "usage: $0 SKIA_CHECKOUT" >&2
  exit 2
fi

skia_dir=$(cd "$1" && pwd)
python_bin=${PYTHON:-python}

for tool in "$python_bin" clang clang++ llvm-ar llvm-dlltool; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "required MinGW build tool is not on PATH: $tool" >&2
    exit 1
  }
done
clang --version | grep -F "clang version 19.1.7"

"$python_bin" "$skia_dir/tools/skia_release/build.py" \
  --skia-dir "$skia_dir" \
  --target mingw \
  --machine x64 \
  --build-type Release \
  --gpu-as-extension

out_dir="$skia_dir/out/Release-mingw-x64"
[[ -d "$out_dir" ]] || {
  echo "Skia did not create the expected output directory: $out_dir" >&2
  exit 1
}

# Ganesh's Direct3D backend needs d3d12 imports, but Kotlin/Native's MinGW sysroot does not ship
# libd3d12.a. Generate only that DLL import map; linking WinLibs' CRT archives would mix runtimes.
cat > "$out_dir/d3d12.def" <<'EOF'
LIBRARY d3d12.dll
EXPORTS
D3D12CreateDevice
D3D12GetDebugInterface
D3D12SerializeRootSignature
D3D12SerializeVersionedRootSignature
D3D12CreateRootSignatureDeserializer
D3D12CreateVersionedRootSignatureDeserializer
EOF
llvm-dlltool -m i386:x86-64 -d "$out_dir/d3d12.def" -l "$out_dir/libd3d12.a"

required_archives=(
  skia skia_ganesh_ext svg skparagraph skshaper skunicode_core skunicode_icu icu
  harfbuzz skresources png jpeg webp webp_sse41 zlib expat
  d3d12allocator raw_ptr allocator_core allocator_base
)
missing=()
for archive in "${required_archives[@]}"; do
  artifact="$out_dir/lib${archive}.a"
  [[ -s "$artifact" ]] || missing+=("$artifact")
done
[[ -s "$out_dir/libd3d12.a" ]] || missing+=("$out_dir/libd3d12.a")

if (( ${#missing[@]} )); then
  echo "Skia build is missing required nonempty MinGW archives:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

printf 'verified %d Skiko MinGW static inputs in %s\n' \
  "$(( ${#required_archives[@]} + 1 ))" "$out_dir"
