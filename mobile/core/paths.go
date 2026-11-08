package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync/atomic"
)

// Where the phone keeps things.
//
// The desktop works this out from where it was started: paths.py reads
// __file__ or sys.executable and walks up. A phone has neither - there is no
// working directory, the APK is not a place files live, and the one writable
// folder is handed over by Android at runtime. So it is set once, by whoever
// wakes up first, and everything else is derived from it.
//
// The layout is the desktop's, with the folders a phone cannot use left out:
//
//	pinned/          the exits the app reads, and the only folder it races
//	configs/         Surfshark's inbox - unpinned, waiting to be pinned
//	windscribe/      Windscribe's inbox, same
//	dropped/         set aside by hand, one shelf per folder they came from
//	.state/          what was measured and what was remembered
//	auth             the Surfshark service credential, two lines
//	auth-windscribe  the Windscribe proxy credential, two lines
//
// auth keeps that name because setup-phone.sh pushes it there, and renaming
// it would quietly strand every phone already set up.
var dataDir atomic.Value // string

// SetDataDir names the directory Android gave this app.
//
// Called before anything reads a file, and from both the Activity and the
// service, because either can be the first thing alive: a phone that comes
// back with the tunnel on starts the service with no window at all.
func SetDataDir(dir string) { dataDir.Store(dir) }

// DataDir is that directory, or "" when nothing has said yet - which is a
// bug in the caller rather than a state worth handling, and shows itself at
// once as a relative path that resolves to nothing.
func DataDir() string {
	if v, ok := dataDir.Load().(string); ok {
		return v
	}
	return ""
}

func under(name string) string { return filepath.Join(DataDir(), name) }

// The folders.
func PinnedDir() string     { return under("pinned") }
func ConfigsDir() string    { return under("configs") }
func WindscribeDir() string { return under("windscribe") }
func DroppedDir() string    { return under("dropped") }
func StateDir() string      { return under(".state") }

// The files inside them.
func ReachPath() string          { return filepath.Join(StateDir(), "reach.json") }
func WindscribeMetaPath() string { return filepath.Join(StateDir(), "windscribe-meta.json") }
func SurfsharkMetaPath() string  { return filepath.Join(StateDir(), "surfshark-meta.json") }
func DroppedIndexPath() string   { return filepath.Join(StateDir(), "dropped.json") }
func AccountsPath() string       { return filepath.Join(StateDir(), "accounts.json") }
func AuthPath() string           { return under("auth") }
func WindscribeAuthPath() string { return under("auth-windscribe") }

// EnsureDirs makes the ones that get written to.
//
// Made rather than assumed: Android creates the files directory itself but
// nothing inside it, and the first thing to want configs/ is whatever is
// about to write into it. A directory that is already there is not an error.
func EnsureDirs() error {
	if DataDir() == "" {
		return os.ErrInvalid
	}
	for _, d := range []string{
		PinnedDir(), ConfigsDir(), WindscribeDir(), DroppedDir(), StateDir(),
	} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	return nil
}

// writeJSON writes through a temporary file beside the real one.
//
// The rule the desktop's _save_reach follows, for a reason a phone makes
// worse: a write interrupted halfway leaves a file that parses as nothing,
// and everything reading it treats "cannot parse" as "never measured".
// Android kills applications mid-write as a matter of routine.
func writeJSON(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
