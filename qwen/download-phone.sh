set -eu
mkdir -p files/qwen
cd files/qwen
echo 'Downloading embd.q4k'
if ! echo 'dd390189724efe994ae3addd267de98fa9d41957d3cb98260022de946c91e286  embd.q4k' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'embd.q4k.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/embd.q4k'
echo 'dd390189724efe994ae3addd267de98fa9d41957d3cb98260022de946c91e286  embd.q4k.part' | sha256sum -c -
mv 'embd.q4k.part' 'embd.q4k'
fi
echo 'Downloading glue.bin'
if ! echo '2536573b08fe6026079a9ad6b1371a66462bdc446e1767edbc15d74e8e2a06df  glue.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'glue.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/glue.bin'
echo '2536573b08fe6026079a9ad6b1371a66462bdc446e1767edbc15d74e8e2a06df  glue.bin.part' | sha256sum -c -
mv 'glue.bin.part' 'glue.bin'
fi
echo 'Downloading tok_merges.txt'
if ! echo '599bab54075088774b1733fde865d5bd747cbcc7a547c5bc12610e874e26f5e3  tok_merges.txt' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'tok_merges.txt.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/tok_merges.txt'
echo '599bab54075088774b1733fde865d5bd747cbcc7a547c5bc12610e874e26f5e3  tok_merges.txt.part' | sha256sum -c -
mv 'tok_merges.txt.part' 'tok_merges.txt'
fi
echo 'Downloading tok_special.txt'
if ! echo '2a90265a3e6deb1254d17f73764ee1b1505be7d7a39c2175aa27818051aca268  tok_special.txt' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'tok_special.txt.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/tok_special.txt'
echo '2a90265a3e6deb1254d17f73764ee1b1505be7d7a39c2175aa27818051aca268  tok_special.txt.part' | sha256sum -c -
mv 'tok_special.txt.part' 'tok_special.txt'
fi
echo 'Downloading tok_vocab.txt'
if ! echo '479c920073825cc49357a884b9b26adcfd96155beb2ea068666c6d991065859f  tok_vocab.txt' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'tok_vocab.txt.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/tok_vocab.txt'
echo '479c920073825cc49357a884b9b26adcfd96155beb2ea068666c6d991065859f  tok_vocab.txt.part' | sha256sum -c -
mv 'tok_vocab.txt.part' 'tok_vocab.txt'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk00.bin'
if ! echo 'cfd8e2d7f199fff4009b9d32287363381d49d722af7937e41b694178995b3fad  dit/blk00.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk00.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk00.bin'
echo 'cfd8e2d7f199fff4009b9d32287363381d49d722af7937e41b694178995b3fad  dit/blk00.bin.part' | sha256sum -c -
mv 'dit/blk00.bin.part' 'dit/blk00.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk01.bin'
if ! echo '90f56a589492460c6bb2d0d4ec24eec71603052a58e1103d7db8eefae7485f13  dit/blk01.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk01.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk01.bin'
echo '90f56a589492460c6bb2d0d4ec24eec71603052a58e1103d7db8eefae7485f13  dit/blk01.bin.part' | sha256sum -c -
mv 'dit/blk01.bin.part' 'dit/blk01.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk02.bin'
if ! echo 'fd37d08def05a6127aed3d07aaf2083e97e34276c7ed3132347be2dd2623bfc1  dit/blk02.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk02.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk02.bin'
echo 'fd37d08def05a6127aed3d07aaf2083e97e34276c7ed3132347be2dd2623bfc1  dit/blk02.bin.part' | sha256sum -c -
mv 'dit/blk02.bin.part' 'dit/blk02.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk03.bin'
if ! echo '5be6b1a9fec0ca5e42de46ed30bb6b19993a9cae0a92b03d58133a51943ff0cb  dit/blk03.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk03.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk03.bin'
echo '5be6b1a9fec0ca5e42de46ed30bb6b19993a9cae0a92b03d58133a51943ff0cb  dit/blk03.bin.part' | sha256sum -c -
mv 'dit/blk03.bin.part' 'dit/blk03.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk04.bin'
if ! echo '488c96c263cec440c0425c317b381b5d787b09f4e29c4070e205a29c0cf46d20  dit/blk04.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk04.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk04.bin'
echo '488c96c263cec440c0425c317b381b5d787b09f4e29c4070e205a29c0cf46d20  dit/blk04.bin.part' | sha256sum -c -
mv 'dit/blk04.bin.part' 'dit/blk04.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk05.bin'
if ! echo '29b3d18db1f107748b25598729ac1fed4f53dc124ff4588ea140ee6e75993547  dit/blk05.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk05.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk05.bin'
echo '29b3d18db1f107748b25598729ac1fed4f53dc124ff4588ea140ee6e75993547  dit/blk05.bin.part' | sha256sum -c -
mv 'dit/blk05.bin.part' 'dit/blk05.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk06.bin'
if ! echo '493ffc9319eb44f0e54a56e53099be1f851e368925bd36dc0743534a21c84b76  dit/blk06.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk06.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk06.bin'
echo '493ffc9319eb44f0e54a56e53099be1f851e368925bd36dc0743534a21c84b76  dit/blk06.bin.part' | sha256sum -c -
mv 'dit/blk06.bin.part' 'dit/blk06.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk07.bin'
if ! echo '84902a0787666642c08abe841310a4ed68b3ccdac83acfebd4ae5055d39bce88  dit/blk07.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk07.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk07.bin'
echo '84902a0787666642c08abe841310a4ed68b3ccdac83acfebd4ae5055d39bce88  dit/blk07.bin.part' | sha256sum -c -
mv 'dit/blk07.bin.part' 'dit/blk07.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk08.bin'
if ! echo 'db25b417c35ed32ce7c1c840e19569dfd4ee1a9de3f5c2e12ddfa8a4fb72e12e  dit/blk08.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk08.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk08.bin'
echo 'db25b417c35ed32ce7c1c840e19569dfd4ee1a9de3f5c2e12ddfa8a4fb72e12e  dit/blk08.bin.part' | sha256sum -c -
mv 'dit/blk08.bin.part' 'dit/blk08.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk09.bin'
if ! echo 'f66e3c3948863236aeaff91f5565847b2471d9a2d12d15b2cc9edbe4b3fcc4a4  dit/blk09.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk09.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk09.bin'
echo 'f66e3c3948863236aeaff91f5565847b2471d9a2d12d15b2cc9edbe4b3fcc4a4  dit/blk09.bin.part' | sha256sum -c -
mv 'dit/blk09.bin.part' 'dit/blk09.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk10.bin'
if ! echo 'c8b802a8d7cb1eeaecc496b77bdf87880843f191cca19521be08157f347a1b7e  dit/blk10.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk10.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk10.bin'
echo 'c8b802a8d7cb1eeaecc496b77bdf87880843f191cca19521be08157f347a1b7e  dit/blk10.bin.part' | sha256sum -c -
mv 'dit/blk10.bin.part' 'dit/blk10.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk11.bin'
if ! echo '931c57d2d3c8365ba7a09bd213631c8ed7df603ab9abc0377b936f793f473a5f  dit/blk11.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk11.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk11.bin'
echo '931c57d2d3c8365ba7a09bd213631c8ed7df603ab9abc0377b936f793f473a5f  dit/blk11.bin.part' | sha256sum -c -
mv 'dit/blk11.bin.part' 'dit/blk11.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk12.bin'
if ! echo 'b3bdf6b22fe05f742b9d4dd702bbc15b86c89165f37f9d7f88266a014391e242  dit/blk12.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk12.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk12.bin'
echo 'b3bdf6b22fe05f742b9d4dd702bbc15b86c89165f37f9d7f88266a014391e242  dit/blk12.bin.part' | sha256sum -c -
mv 'dit/blk12.bin.part' 'dit/blk12.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk13.bin'
if ! echo '93bc7ae3d11d73193ce7404fe8f821bd0913bea52c4c51d528bd8778c6ba1da7  dit/blk13.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk13.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk13.bin'
echo '93bc7ae3d11d73193ce7404fe8f821bd0913bea52c4c51d528bd8778c6ba1da7  dit/blk13.bin.part' | sha256sum -c -
mv 'dit/blk13.bin.part' 'dit/blk13.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk14.bin'
if ! echo '3d4b107ee22b6dde064d2f4d4c9ca9e7efc0cd503ff25f06e34b2a16b64e57c1  dit/blk14.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk14.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk14.bin'
echo '3d4b107ee22b6dde064d2f4d4c9ca9e7efc0cd503ff25f06e34b2a16b64e57c1  dit/blk14.bin.part' | sha256sum -c -
mv 'dit/blk14.bin.part' 'dit/blk14.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk15.bin'
if ! echo 'c29ed306177010de356d9f6189ef2b36a79853817928f6dfacf6278cc9a0daa2  dit/blk15.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk15.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk15.bin'
echo 'c29ed306177010de356d9f6189ef2b36a79853817928f6dfacf6278cc9a0daa2  dit/blk15.bin.part' | sha256sum -c -
mv 'dit/blk15.bin.part' 'dit/blk15.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk16.bin'
if ! echo 'f8bb250613056d220bd3dc78eb6055941580d4a95b6dfef19577f148bb400113  dit/blk16.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk16.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk16.bin'
echo 'f8bb250613056d220bd3dc78eb6055941580d4a95b6dfef19577f148bb400113  dit/blk16.bin.part' | sha256sum -c -
mv 'dit/blk16.bin.part' 'dit/blk16.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk17.bin'
if ! echo 'eb75def0062420e4e097de457b6a8aa45776adfa99664526727a954ac1bd82c5  dit/blk17.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk17.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk17.bin'
echo 'eb75def0062420e4e097de457b6a8aa45776adfa99664526727a954ac1bd82c5  dit/blk17.bin.part' | sha256sum -c -
mv 'dit/blk17.bin.part' 'dit/blk17.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk18.bin'
if ! echo 'c2c59db39e37c8820328b67aaf8a6d891bead3e07d417f9cdbb27249cc12a549  dit/blk18.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk18.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk18.bin'
echo 'c2c59db39e37c8820328b67aaf8a6d891bead3e07d417f9cdbb27249cc12a549  dit/blk18.bin.part' | sha256sum -c -
mv 'dit/blk18.bin.part' 'dit/blk18.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk19.bin'
if ! echo '1e64234af7f97dc900d7e81260e340455d9bb92da91cf9a1089a7b788b398da1  dit/blk19.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk19.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk19.bin'
echo '1e64234af7f97dc900d7e81260e340455d9bb92da91cf9a1089a7b788b398da1  dit/blk19.bin.part' | sha256sum -c -
mv 'dit/blk19.bin.part' 'dit/blk19.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk20.bin'
if ! echo '920352757a7cb013cbffef11d24ce4c0b7b296673c73a4e4990ef6a323fdebe4  dit/blk20.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk20.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk20.bin'
echo '920352757a7cb013cbffef11d24ce4c0b7b296673c73a4e4990ef6a323fdebe4  dit/blk20.bin.part' | sha256sum -c -
mv 'dit/blk20.bin.part' 'dit/blk20.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk21.bin'
if ! echo 'be3c6c9e76ae4b9e5918f68dcb2d4d068cd3237f08e7cf8488524afde52e0dfb  dit/blk21.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk21.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk21.bin'
echo 'be3c6c9e76ae4b9e5918f68dcb2d4d068cd3237f08e7cf8488524afde52e0dfb  dit/blk21.bin.part' | sha256sum -c -
mv 'dit/blk21.bin.part' 'dit/blk21.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk22.bin'
if ! echo 'b7c0cb562cf858cc7be42dca2cad8d6d9625ad637dd3b9072218318678cb0bb1  dit/blk22.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk22.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk22.bin'
echo 'b7c0cb562cf858cc7be42dca2cad8d6d9625ad637dd3b9072218318678cb0bb1  dit/blk22.bin.part' | sha256sum -c -
mv 'dit/blk22.bin.part' 'dit/blk22.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk23.bin'
if ! echo '0f3caaa4050e4c612184ba2c8ecda4b7583b2a72431446aa034513f961e0b7f0  dit/blk23.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk23.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk23.bin'
echo '0f3caaa4050e4c612184ba2c8ecda4b7583b2a72431446aa034513f961e0b7f0  dit/blk23.bin.part' | sha256sum -c -
mv 'dit/blk23.bin.part' 'dit/blk23.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk24.bin'
if ! echo 'f431edd689d8c8350253c4552870307303d987d2b7109ff6fb8848c84fb48758  dit/blk24.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk24.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk24.bin'
echo 'f431edd689d8c8350253c4552870307303d987d2b7109ff6fb8848c84fb48758  dit/blk24.bin.part' | sha256sum -c -
mv 'dit/blk24.bin.part' 'dit/blk24.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk25.bin'
if ! echo '29cf58128331ad647c6e93aa18590eee16bcd64d8677748ed324e646def03b0a  dit/blk25.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk25.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk25.bin'
echo '29cf58128331ad647c6e93aa18590eee16bcd64d8677748ed324e646def03b0a  dit/blk25.bin.part' | sha256sum -c -
mv 'dit/blk25.bin.part' 'dit/blk25.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk26.bin'
if ! echo '5c2126b0d4b43a2863281b4f5c63f235cbdb801692fd577e2cf8a0bd3267452c  dit/blk26.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk26.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk26.bin'
echo '5c2126b0d4b43a2863281b4f5c63f235cbdb801692fd577e2cf8a0bd3267452c  dit/blk26.bin.part' | sha256sum -c -
mv 'dit/blk26.bin.part' 'dit/blk26.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk27.bin'
if ! echo 'b0c9faae3f4998ad7c1e2cf2d09dec3b1b502b4a3d1271eabb4d926b928d1bdc  dit/blk27.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk27.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk27.bin'
echo 'b0c9faae3f4998ad7c1e2cf2d09dec3b1b502b4a3d1271eabb4d926b928d1bdc  dit/blk27.bin.part' | sha256sum -c -
mv 'dit/blk27.bin.part' 'dit/blk27.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk28.bin'
if ! echo '91f97931754081116c8d84a815d0022aeb76166b78ce9ccdfaab3c7ba8e51ab7  dit/blk28.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk28.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk28.bin'
echo '91f97931754081116c8d84a815d0022aeb76166b78ce9ccdfaab3c7ba8e51ab7  dit/blk28.bin.part' | sha256sum -c -
mv 'dit/blk28.bin.part' 'dit/blk28.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk29.bin'
if ! echo '22c39d57afa5c81bd9d4da319c2c278132cbe417d11a9b85433a6c98e231019d  dit/blk29.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk29.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk29.bin'
echo '22c39d57afa5c81bd9d4da319c2c278132cbe417d11a9b85433a6c98e231019d  dit/blk29.bin.part' | sha256sum -c -
mv 'dit/blk29.bin.part' 'dit/blk29.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk30.bin'
if ! echo '73782432f5dcdeb604a423f8c5c203110f54dbeaed76f738c101d2396c72cbda  dit/blk30.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk30.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk30.bin'
echo '73782432f5dcdeb604a423f8c5c203110f54dbeaed76f738c101d2396c72cbda  dit/blk30.bin.part' | sha256sum -c -
mv 'dit/blk30.bin.part' 'dit/blk30.bin'
fi
mkdir -p 'dit'
echo 'Downloading dit/blk31.bin'
if ! echo 'b027ceba8e45a7a34b51ae797f048d4a3f53bcd09a9c7bb3c45dfc74cff096db  dit/blk31.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'dit/blk31.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/dit/blk31.bin'
echo 'b027ceba8e45a7a34b51ae797f048d4a3f53bcd09a9c7bb3c45dfc74cff096db  dit/blk31.bin.part' | sha256sum -c -
mv 'dit/blk31.bin.part' 'dit/blk31.bin'
fi
mkdir -p 'vae'
echo 'Downloading vae/enc_t32.bin'
if ! echo 'f141baeb36c2e3ca2b25a958b1e3998f6c4bfed244472814feebb681e0bdc629  vae/enc_t32.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vae/enc_t32.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vae/enc_t32.bin'
echo 'f141baeb36c2e3ca2b25a958b1e3998f6c4bfed244472814feebb681e0bdc629  vae/enc_t32.bin.part' | sha256sum -c -
mv 'vae/enc_t32.bin.part' 'vae/enc_t32.bin'
fi
mkdir -p 'vae'
echo 'Downloading vae/vae_t32.bin'
if ! echo '28ae2ce224cdd0381e75c3e56a1586608b2af560a5d52fd58c1b471525fa6926  vae/vae_t32.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vae/vae_t32.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vae/vae_t32.bin'
echo '28ae2ce224cdd0381e75c3e56a1586608b2af560a5d52fd58c1b471525fa6926  vae/vae_t32.bin.part' | sha256sum -c -
mv 'vae/vae_t32.bin.part' 'vae/vae_t32.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te00.bin'
if ! echo '17c06c5eb24ee51725d54e953cacc95fdd0616551d88aa3eb6227747ca785659  te/te00.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te00.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te00.bin'
echo '17c06c5eb24ee51725d54e953cacc95fdd0616551d88aa3eb6227747ca785659  te/te00.bin.part' | sha256sum -c -
mv 'te/te00.bin.part' 'te/te00.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te01.bin'
if ! echo '635cb76f9638af09fd66dc905ea8500ced9ca1c905b17e5942a5fd87870f8ac9  te/te01.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te01.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te01.bin'
echo '635cb76f9638af09fd66dc905ea8500ced9ca1c905b17e5942a5fd87870f8ac9  te/te01.bin.part' | sha256sum -c -
mv 'te/te01.bin.part' 'te/te01.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te02.bin'
if ! echo 'f7d14a5c1663dff74781677615ec0e9459109591754dee80c1856ee69507b9b7  te/te02.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te02.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te02.bin'
echo 'f7d14a5c1663dff74781677615ec0e9459109591754dee80c1856ee69507b9b7  te/te02.bin.part' | sha256sum -c -
mv 'te/te02.bin.part' 'te/te02.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te03.bin'
if ! echo '1034c3aca44e37dfb9eddedf2d359c85e6367efdba2ebb62e488c66d68b9f85a  te/te03.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te03.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te03.bin'
echo '1034c3aca44e37dfb9eddedf2d359c85e6367efdba2ebb62e488c66d68b9f85a  te/te03.bin.part' | sha256sum -c -
mv 'te/te03.bin.part' 'te/te03.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te04.bin'
if ! echo 'd720bdb0dc96c19471d174c68d7040829a6c39112fe83a7a846f1721bdd4f0a2  te/te04.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te04.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te04.bin'
echo 'd720bdb0dc96c19471d174c68d7040829a6c39112fe83a7a846f1721bdd4f0a2  te/te04.bin.part' | sha256sum -c -
mv 'te/te04.bin.part' 'te/te04.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te05.bin'
if ! echo 'fb6b9d3682105a76295d8c1e146ad92721a77d9daae38c9b2cf1fca32fde276c  te/te05.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te05.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te05.bin'
echo 'fb6b9d3682105a76295d8c1e146ad92721a77d9daae38c9b2cf1fca32fde276c  te/te05.bin.part' | sha256sum -c -
mv 'te/te05.bin.part' 'te/te05.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te06.bin'
if ! echo '86f81ea553febcb903ff52310a8f1acd7fe895c49ed4008e0a85f38abe356218  te/te06.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te06.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te06.bin'
echo '86f81ea553febcb903ff52310a8f1acd7fe895c49ed4008e0a85f38abe356218  te/te06.bin.part' | sha256sum -c -
mv 'te/te06.bin.part' 'te/te06.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te07.bin'
if ! echo 'da9bdaf70de35b9535d5e64af6546f6e9e945d5c29471858b988f12695d0a0e9  te/te07.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te07.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te07.bin'
echo 'da9bdaf70de35b9535d5e64af6546f6e9e945d5c29471858b988f12695d0a0e9  te/te07.bin.part' | sha256sum -c -
mv 'te/te07.bin.part' 'te/te07.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te08.bin'
if ! echo '8526b84bfddf88794fd272f15fec1a38fdd97ef32c9c7cae1f188e69c510155f  te/te08.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te08.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te08.bin'
echo '8526b84bfddf88794fd272f15fec1a38fdd97ef32c9c7cae1f188e69c510155f  te/te08.bin.part' | sha256sum -c -
mv 'te/te08.bin.part' 'te/te08.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te09.bin'
if ! echo '37f807dc299af897283f1252c19bbc46f614956284f2cbf7ba77890b640e7dbe  te/te09.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te09.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te09.bin'
echo '37f807dc299af897283f1252c19bbc46f614956284f2cbf7ba77890b640e7dbe  te/te09.bin.part' | sha256sum -c -
mv 'te/te09.bin.part' 'te/te09.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te10.bin'
if ! echo '7c2bafff1c5d50324dd5cf633e54091e05667195e0d8073222e75b492091780e  te/te10.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te10.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te10.bin'
echo '7c2bafff1c5d50324dd5cf633e54091e05667195e0d8073222e75b492091780e  te/te10.bin.part' | sha256sum -c -
mv 'te/te10.bin.part' 'te/te10.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te11.bin'
if ! echo '27219a6ffaee9ed440471c625d42b459381c49e7867e919c47ffe18ab7c6f26e  te/te11.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te11.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te11.bin'
echo '27219a6ffaee9ed440471c625d42b459381c49e7867e919c47ffe18ab7c6f26e  te/te11.bin.part' | sha256sum -c -
mv 'te/te11.bin.part' 'te/te11.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te12.bin'
if ! echo '7b4b27decb1e886312151f9b247f747693747860b4fdbffb348b7ade02347412  te/te12.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te12.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te12.bin'
echo '7b4b27decb1e886312151f9b247f747693747860b4fdbffb348b7ade02347412  te/te12.bin.part' | sha256sum -c -
mv 'te/te12.bin.part' 'te/te12.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te13.bin'
if ! echo 'f1fe24fafbc40e0bcf992f3946e5349057d1a19ecfd64b0f463ba952a649f1dd  te/te13.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te13.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te13.bin'
echo 'f1fe24fafbc40e0bcf992f3946e5349057d1a19ecfd64b0f463ba952a649f1dd  te/te13.bin.part' | sha256sum -c -
mv 'te/te13.bin.part' 'te/te13.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te14.bin'
if ! echo '2b53f9391ab5e1033b3ed9d2bebb7c100458e41dadc71e059f098f1dccbb5426  te/te14.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te14.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te14.bin'
echo '2b53f9391ab5e1033b3ed9d2bebb7c100458e41dadc71e059f098f1dccbb5426  te/te14.bin.part' | sha256sum -c -
mv 'te/te14.bin.part' 'te/te14.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te15.bin'
if ! echo '2aefb413c6f0ec68f89d57f7cb374b4e5018c45f0b4b0724d4e7ae486830aa37  te/te15.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te15.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te15.bin'
echo '2aefb413c6f0ec68f89d57f7cb374b4e5018c45f0b4b0724d4e7ae486830aa37  te/te15.bin.part' | sha256sum -c -
mv 'te/te15.bin.part' 'te/te15.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te16.bin'
if ! echo 'c97da271055b40baa066188f10d84451f70797ef8555b279810c836b87cab21f  te/te16.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te16.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te16.bin'
echo 'c97da271055b40baa066188f10d84451f70797ef8555b279810c836b87cab21f  te/te16.bin.part' | sha256sum -c -
mv 'te/te16.bin.part' 'te/te16.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te17.bin'
if ! echo '705f3fbe5f46d892ec0ca8254dcc6b068d04033336c0c52e630db4af59a866e4  te/te17.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te17.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te17.bin'
echo '705f3fbe5f46d892ec0ca8254dcc6b068d04033336c0c52e630db4af59a866e4  te/te17.bin.part' | sha256sum -c -
mv 'te/te17.bin.part' 'te/te17.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te18.bin'
if ! echo '30e4b5288e2eae78b1869f7cbd462a62083a9fda181067c1370454e80799349e  te/te18.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te18.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te18.bin'
echo '30e4b5288e2eae78b1869f7cbd462a62083a9fda181067c1370454e80799349e  te/te18.bin.part' | sha256sum -c -
mv 'te/te18.bin.part' 'te/te18.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te19.bin'
if ! echo 'befd6df52c8f4083ee3993ddc0b282721e8ec5ea874ecb8a3b7f62f6183e4c5b  te/te19.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te19.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te19.bin'
echo 'befd6df52c8f4083ee3993ddc0b282721e8ec5ea874ecb8a3b7f62f6183e4c5b  te/te19.bin.part' | sha256sum -c -
mv 'te/te19.bin.part' 'te/te19.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te20.bin'
if ! echo '769010860792d83d0874217f6811ddec971ee430307af93cda52689ad215f4b5  te/te20.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te20.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te20.bin'
echo '769010860792d83d0874217f6811ddec971ee430307af93cda52689ad215f4b5  te/te20.bin.part' | sha256sum -c -
mv 'te/te20.bin.part' 'te/te20.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te21.bin'
if ! echo '360a665b63702298d658ab028bc4f30eba7cec1c50e58bfa86750744b8fd03d1  te/te21.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te21.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te21.bin'
echo '360a665b63702298d658ab028bc4f30eba7cec1c50e58bfa86750744b8fd03d1  te/te21.bin.part' | sha256sum -c -
mv 'te/te21.bin.part' 'te/te21.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te22.bin'
if ! echo 'e930102875a87655692a18694c35d8429bb5313207d9277c0127ae4a00b2f20c  te/te22.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te22.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te22.bin'
echo 'e930102875a87655692a18694c35d8429bb5313207d9277c0127ae4a00b2f20c  te/te22.bin.part' | sha256sum -c -
mv 'te/te22.bin.part' 'te/te22.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te23.bin'
if ! echo '1033c338121731b2f125383f02ff6ff79ed81098b1d7157371c756d6bb9fff8d  te/te23.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te23.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te23.bin'
echo '1033c338121731b2f125383f02ff6ff79ed81098b1d7157371c756d6bb9fff8d  te/te23.bin.part' | sha256sum -c -
mv 'te/te23.bin.part' 'te/te23.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te24.bin'
if ! echo '3a83c8925372229e26c02cacea94c686dbfef8c96ce59c7cfb1fadcd771da6fc  te/te24.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te24.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te24.bin'
echo '3a83c8925372229e26c02cacea94c686dbfef8c96ce59c7cfb1fadcd771da6fc  te/te24.bin.part' | sha256sum -c -
mv 'te/te24.bin.part' 'te/te24.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te25.bin'
if ! echo '2ffab8bd9bdc877100d59c5c1720d0907d7089c8b845d6dc6ae9293547d116b3  te/te25.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te25.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te25.bin'
echo '2ffab8bd9bdc877100d59c5c1720d0907d7089c8b845d6dc6ae9293547d116b3  te/te25.bin.part' | sha256sum -c -
mv 'te/te25.bin.part' 'te/te25.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te26.bin'
if ! echo '4121fd9028d843de65e116aad38c2fbdc9d0fd6bc1e328b4d0a8135f57784a76  te/te26.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te26.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te26.bin'
echo '4121fd9028d843de65e116aad38c2fbdc9d0fd6bc1e328b4d0a8135f57784a76  te/te26.bin.part' | sha256sum -c -
mv 'te/te26.bin.part' 'te/te26.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te27.bin'
if ! echo '8de28017d73339a7f0dbfc2964dc5290d049bddbe888dde90fea0b4a1de7ae33  te/te27.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te27.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te27.bin'
echo '8de28017d73339a7f0dbfc2964dc5290d049bddbe888dde90fea0b4a1de7ae33  te/te27.bin.part' | sha256sum -c -
mv 'te/te27.bin.part' 'te/te27.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te28.bin'
if ! echo '7e663e3d93e88bd5288eb0bd76a92a2893d1d24b62c849abe8e0d8ad2fd06cd8  te/te28.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te28.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te28.bin'
echo '7e663e3d93e88bd5288eb0bd76a92a2893d1d24b62c849abe8e0d8ad2fd06cd8  te/te28.bin.part' | sha256sum -c -
mv 'te/te28.bin.part' 'te/te28.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te29.bin'
if ! echo 'ffbe1c1cf9c04a02de1fdc1b19265e45b4926033530e5b12ed7f52f11fc6fb0a  te/te29.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te29.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te29.bin'
echo 'ffbe1c1cf9c04a02de1fdc1b19265e45b4926033530e5b12ed7f52f11fc6fb0a  te/te29.bin.part' | sha256sum -c -
mv 'te/te29.bin.part' 'te/te29.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te30.bin'
if ! echo '7f7a812c802253fb79c5a7064d4ca66a4507a0bf6f195c48bd668fde5c7b9bdc  te/te30.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te30.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te30.bin'
echo '7f7a812c802253fb79c5a7064d4ca66a4507a0bf6f195c48bd668fde5c7b9bdc  te/te30.bin.part' | sha256sum -c -
mv 'te/te30.bin.part' 'te/te30.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te31.bin'
if ! echo '5d7de418790096294489c81177a3160a1ddec7726eed40844af6ccb26573cdd1  te/te31.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te31.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te31.bin'
echo '5d7de418790096294489c81177a3160a1ddec7726eed40844af6ccb26573cdd1  te/te31.bin.part' | sha256sum -c -
mv 'te/te31.bin.part' 'te/te31.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te32.bin'
if ! echo '67d691dc831e48bf24e13e242e705b83046517d6bee6b099b17320e9981b7908  te/te32.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te32.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te32.bin'
echo '67d691dc831e48bf24e13e242e705b83046517d6bee6b099b17320e9981b7908  te/te32.bin.part' | sha256sum -c -
mv 'te/te32.bin.part' 'te/te32.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te33.bin'
if ! echo '8e7378828e3b46a708beb3b21ab4b22b75b4f770385c86321aafb58672324850  te/te33.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te33.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te33.bin'
echo '8e7378828e3b46a708beb3b21ab4b22b75b4f770385c86321aafb58672324850  te/te33.bin.part' | sha256sum -c -
mv 'te/te33.bin.part' 'te/te33.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te34.bin'
if ! echo '3f84026015ade1fad12a7074776e0d9ed8c8a9d2d48bc75c418f857cd5b71c40  te/te34.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te34.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te34.bin'
echo '3f84026015ade1fad12a7074776e0d9ed8c8a9d2d48bc75c418f857cd5b71c40  te/te34.bin.part' | sha256sum -c -
mv 'te/te34.bin.part' 'te/te34.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te35.bin'
if ! echo 'ed6cd7976404b010553b2822c62636d2fc4cbd5e928bb45f2764b6e30c1ddcd5  te/te35.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te35.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te35.bin'
echo 'ed6cd7976404b010553b2822c62636d2fc4cbd5e928bb45f2764b6e30c1ddcd5  te/te35.bin.part' | sha256sum -c -
mv 'te/te35.bin.part' 'te/te35.bin'
fi
mkdir -p 'te'
echo 'Downloading te/te36.bin'
if ! echo '32c4704f092def315461a5da6ca1cad98348af9f2ea926047037e34c8a28ebc0  te/te36.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'te/te36.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/te/te36.bin'
echo '32c4704f092def315461a5da6ca1cad98348af9f2ea926047037e34c8a28ebc0  te/te36.bin.part' | sha256sum -c -
mv 'te/te36.bin.part' 'te/te36.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit00.bin'
if ! echo 'c1fe538024f0a2f2abe39fd3eb9024138b9bd229cef94025d2c9db88fa4bf374  vit/vit00.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit00.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit00.bin'
echo 'c1fe538024f0a2f2abe39fd3eb9024138b9bd229cef94025d2c9db88fa4bf374  vit/vit00.bin.part' | sha256sum -c -
mv 'vit/vit00.bin.part' 'vit/vit00.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit01.bin'
if ! echo 'af380e9f857c1c9326f454f6259ea435674b93a89ff8bd4e2c03513af750ba98  vit/vit01.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit01.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit01.bin'
echo 'af380e9f857c1c9326f454f6259ea435674b93a89ff8bd4e2c03513af750ba98  vit/vit01.bin.part' | sha256sum -c -
mv 'vit/vit01.bin.part' 'vit/vit01.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit02.bin'
if ! echo 'dbee485080efaf048c283693332b97d507f5c45c845ac4437bd11d7b151a89bf  vit/vit02.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit02.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit02.bin'
echo 'dbee485080efaf048c283693332b97d507f5c45c845ac4437bd11d7b151a89bf  vit/vit02.bin.part' | sha256sum -c -
mv 'vit/vit02.bin.part' 'vit/vit02.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit03.bin'
if ! echo 'c453a423a38af14d9bff895b2ee02bb07a834b2a2e50c30264546eec92316e0e  vit/vit03.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit03.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit03.bin'
echo 'c453a423a38af14d9bff895b2ee02bb07a834b2a2e50c30264546eec92316e0e  vit/vit03.bin.part' | sha256sum -c -
mv 'vit/vit03.bin.part' 'vit/vit03.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit04.bin'
if ! echo '18e31fc289c55fc9b555b396ff69cbcffb9d436ee7cf5a889dabe3f8f3df66a9  vit/vit04.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit04.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit04.bin'
echo '18e31fc289c55fc9b555b396ff69cbcffb9d436ee7cf5a889dabe3f8f3df66a9  vit/vit04.bin.part' | sha256sum -c -
mv 'vit/vit04.bin.part' 'vit/vit04.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit05.bin'
if ! echo 'a27ddd1afb22fc87a8df22573cb611fa7951ccf0bf86d37acf74661f1c13974c  vit/vit05.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit05.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit05.bin'
echo 'a27ddd1afb22fc87a8df22573cb611fa7951ccf0bf86d37acf74661f1c13974c  vit/vit05.bin.part' | sha256sum -c -
mv 'vit/vit05.bin.part' 'vit/vit05.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit06.bin'
if ! echo '0e2e88b584c3515f04df4b29aa84267662d5c3ee394d9347958746e090d764f7  vit/vit06.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit06.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit06.bin'
echo '0e2e88b584c3515f04df4b29aa84267662d5c3ee394d9347958746e090d764f7  vit/vit06.bin.part' | sha256sum -c -
mv 'vit/vit06.bin.part' 'vit/vit06.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit07.bin'
if ! echo 'b83aaf49907e332a8bcff9c54162b05d5aaeb7eb5f748d387701e6681618a5a1  vit/vit07.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit07.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit07.bin'
echo 'b83aaf49907e332a8bcff9c54162b05d5aaeb7eb5f748d387701e6681618a5a1  vit/vit07.bin.part' | sha256sum -c -
mv 'vit/vit07.bin.part' 'vit/vit07.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit08.bin'
if ! echo 'b00fb91946fa924e326f1cbd476321fdc1e14b3570c18ae822356496cc0d1383  vit/vit08.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit08.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit08.bin'
echo 'b00fb91946fa924e326f1cbd476321fdc1e14b3570c18ae822356496cc0d1383  vit/vit08.bin.part' | sha256sum -c -
mv 'vit/vit08.bin.part' 'vit/vit08.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit09.bin'
if ! echo '7d55f3799fd546ca99771ce3abe1b2e2f202d775154225f5d850d0c7569d6cfc  vit/vit09.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit09.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit09.bin'
echo '7d55f3799fd546ca99771ce3abe1b2e2f202d775154225f5d850d0c7569d6cfc  vit/vit09.bin.part' | sha256sum -c -
mv 'vit/vit09.bin.part' 'vit/vit09.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit10.bin'
if ! echo 'ba9f76d6982771cccf62c54d51cb9ba5ae45a071d9dab99d1e529e9a8e885e72  vit/vit10.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit10.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit10.bin'
echo 'ba9f76d6982771cccf62c54d51cb9ba5ae45a071d9dab99d1e529e9a8e885e72  vit/vit10.bin.part' | sha256sum -c -
mv 'vit/vit10.bin.part' 'vit/vit10.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit11.bin'
if ! echo 'cbc65641855ba73ff065d9c26f614687308da4d15790f2753f953adb46163616  vit/vit11.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit11.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit11.bin'
echo 'cbc65641855ba73ff065d9c26f614687308da4d15790f2753f953adb46163616  vit/vit11.bin.part' | sha256sum -c -
mv 'vit/vit11.bin.part' 'vit/vit11.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit12.bin'
if ! echo 'ae9b74226465284bc2a343ae71e0f7989d89d05ea053b079294d144a872f98c3  vit/vit12.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit12.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit12.bin'
echo 'ae9b74226465284bc2a343ae71e0f7989d89d05ea053b079294d144a872f98c3  vit/vit12.bin.part' | sha256sum -c -
mv 'vit/vit12.bin.part' 'vit/vit12.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit13.bin'
if ! echo '220331c2159bf97e869ab7d47b3101e976c652c45947cb03d27c4ffb02cfebcc  vit/vit13.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit13.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit13.bin'
echo '220331c2159bf97e869ab7d47b3101e976c652c45947cb03d27c4ffb02cfebcc  vit/vit13.bin.part' | sha256sum -c -
mv 'vit/vit13.bin.part' 'vit/vit13.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit14.bin'
if ! echo '006bdbb84c56af4a7bc26043f6c3932db6ba44be673e950862cb946bdf055b43  vit/vit14.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit14.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit14.bin'
echo '006bdbb84c56af4a7bc26043f6c3932db6ba44be673e950862cb946bdf055b43  vit/vit14.bin.part' | sha256sum -c -
mv 'vit/vit14.bin.part' 'vit/vit14.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit15.bin'
if ! echo 'ec6614eb843ba360cc974eacef067eeb4f486af95a5568fa68d283557ff08e62  vit/vit15.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit15.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit15.bin'
echo 'ec6614eb843ba360cc974eacef067eeb4f486af95a5568fa68d283557ff08e62  vit/vit15.bin.part' | sha256sum -c -
mv 'vit/vit15.bin.part' 'vit/vit15.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit16.bin'
if ! echo '56998804776bbfa4348e980e2b316d9b6bfb09ca955c8bfc4e104c07d0e3206a  vit/vit16.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit16.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit16.bin'
echo '56998804776bbfa4348e980e2b316d9b6bfb09ca955c8bfc4e104c07d0e3206a  vit/vit16.bin.part' | sha256sum -c -
mv 'vit/vit16.bin.part' 'vit/vit16.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit17.bin'
if ! echo 'f35619351748b34b6745d0842a4f65a9fbc4f1b40f71c0f23eb3434d2ded7521  vit/vit17.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit17.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit17.bin'
echo 'f35619351748b34b6745d0842a4f65a9fbc4f1b40f71c0f23eb3434d2ded7521  vit/vit17.bin.part' | sha256sum -c -
mv 'vit/vit17.bin.part' 'vit/vit17.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit18.bin'
if ! echo 'fcb99f16ebaf43707d2990b50110a149962ac7474fcd7de9afad0e700020a30a  vit/vit18.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit18.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit18.bin'
echo 'fcb99f16ebaf43707d2990b50110a149962ac7474fcd7de9afad0e700020a30a  vit/vit18.bin.part' | sha256sum -c -
mv 'vit/vit18.bin.part' 'vit/vit18.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit19.bin'
if ! echo '3c877e6afff5771d370b65d97c892a49c8c8288412e7d23047028aab54198ce5  vit/vit19.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit19.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit19.bin'
echo '3c877e6afff5771d370b65d97c892a49c8c8288412e7d23047028aab54198ce5  vit/vit19.bin.part' | sha256sum -c -
mv 'vit/vit19.bin.part' 'vit/vit19.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit20.bin'
if ! echo '54e8a13d91fa9a4e7577453daa3853e089fc7ec642a1661d77a3f98f5b3aabc2  vit/vit20.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit20.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit20.bin'
echo '54e8a13d91fa9a4e7577453daa3853e089fc7ec642a1661d77a3f98f5b3aabc2  vit/vit20.bin.part' | sha256sum -c -
mv 'vit/vit20.bin.part' 'vit/vit20.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit21.bin'
if ! echo 'bb2ba39770f216a344ad5f480ccb01a42bf040d291ad2df4bd13bc049075728e  vit/vit21.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit21.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit21.bin'
echo 'bb2ba39770f216a344ad5f480ccb01a42bf040d291ad2df4bd13bc049075728e  vit/vit21.bin.part' | sha256sum -c -
mv 'vit/vit21.bin.part' 'vit/vit21.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit22.bin'
if ! echo '4fda496fac35dd537693196ab0c420d70e7e6c6be10bbb6ff26be7f9a4809cbf  vit/vit22.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit22.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit22.bin'
echo '4fda496fac35dd537693196ab0c420d70e7e6c6be10bbb6ff26be7f9a4809cbf  vit/vit22.bin.part' | sha256sum -c -
mv 'vit/vit22.bin.part' 'vit/vit22.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit23.bin'
if ! echo '1b24d28c16b367c4e5fd08839a7014dd08292157a5bda635b5a677fa88d05a67  vit/vit23.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit23.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit23.bin'
echo '1b24d28c16b367c4e5fd08839a7014dd08292157a5bda635b5a677fa88d05a67  vit/vit23.bin.part' | sha256sum -c -
mv 'vit/vit23.bin.part' 'vit/vit23.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit24.bin'
if ! echo '3a38f044ad9b32e87d48becc2282e6ebe63f4a9d445c72ebbab5228333dc207f  vit/vit24.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit24.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit24.bin'
echo '3a38f044ad9b32e87d48becc2282e6ebe63f4a9d445c72ebbab5228333dc207f  vit/vit24.bin.part' | sha256sum -c -
mv 'vit/vit24.bin.part' 'vit/vit24.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit25.bin'
if ! echo 'a81b361023b89ad86247bdb2c2452e07f7e8acc1d11e8a18d2969f356ffe5185  vit/vit25.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit25.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit25.bin'
echo 'a81b361023b89ad86247bdb2c2452e07f7e8acc1d11e8a18d2969f356ffe5185  vit/vit25.bin.part' | sha256sum -c -
mv 'vit/vit25.bin.part' 'vit/vit25.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit26.bin'
if ! echo '5f1358eb5059a269285b840626c6b9249cd9fd917c6d4f89cb7290faebec4bcd  vit/vit26.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit26.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit26.bin'
echo '5f1358eb5059a269285b840626c6b9249cd9fd917c6d4f89cb7290faebec4bcd  vit/vit26.bin.part' | sha256sum -c -
mv 'vit/vit26.bin.part' 'vit/vit26.bin'
fi
mkdir -p 'vit'
echo 'Downloading vit/vit_glue.bin'
if ! echo '3df873b69000259a489dacc91f5ab5effd7751b03932a9842d1f21505714c34f  vit/vit_glue.bin' | sha256sum -c - >/dev/null 2>&1; then
curl --resolve huggingface.co:443:18.164.32.55 --silent --show-error --fail --location --retry 60 --retry-delay 3 --retry-all-errors --connect-timeout 30 --continue-at - --output 'vit/vit_glue.bin.part' 'https://huggingface.co/ayanoby/hexanima-qwen-packs/resolve/216a2a1c0b784ca3f382c9290bc24cb052a04fd7/vit/vit_glue.bin'
echo '3df873b69000259a489dacc91f5ab5effd7751b03932a9842d1f21505714c34f  vit/vit_glue.bin.part' | sha256sum -c -
mv 'vit/vit_glue.bin.part' 'vit/vit_glue.bin'
fi
echo 'QWEN_PACK_DOWNLOAD_VERIFIED 216a2a1c0b784ca3f382c9290bc24cb052a04fd7'
