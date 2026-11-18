# Visual assets

| File | Purpose | Where it is used |
| --- | --- | --- |
| `architecture.svg` / `architecture.png` | Where the SDK sits between client protocols, the gateway and upstream providers | `README.md`, `README.zh-CN.md` |
| `social-preview.svg` / `social-preview.png` | GitHub repository social preview, 1280×640 | Uploaded manually in repository settings |

SVG is the source; PNG is generated from it so GitHub and editors render it consistently.

Regenerate a PNG after editing the SVG (Chrome is used as the renderer, so no extra tooling is
required):

```shell
chrome --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
  --window-size=952,436 --screenshot=docs/assets/architecture.png \
  file:///<abs-path>/docs/assets/architecture.svg
```

For the social preview use `--force-device-scale-factor=1 --window-size=1280,640`. The GitHub social
preview image cannot be set through the REST API; upload it under **Settings → General → Social
preview**.
