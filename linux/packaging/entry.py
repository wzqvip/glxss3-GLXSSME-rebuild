#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PyInstaller 打包入口。

与 pyproject.toml 的 `glxss = "glxss.cli:main"` 保持一致；单独放一个脚本，
避免 spec 直接引用 site-packages 里生成的 console_script。
"""

import sys

from glxss.cli import main


if __name__ == "__main__":
    sys.exit(main())
