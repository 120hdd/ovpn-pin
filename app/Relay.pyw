r"""Double-click this instead of main.py.

Windows runs .py with python.exe, which always opens a console window beside
the app. It runs .pyw with pythonw.exe, which does not. Nothing else differs -
this starts main.py exactly as running it directly would.

The cost of the quiet is the tracebacks: pythonw has no stderr, so a crash
closes the window with nothing to read. When something is wrong, run
`python app\main.py` from a terminal and watch it fail there instead.
"""

import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
runpy.run_path(os.path.join(HERE, 'main.py'), run_name='__main__')
