package relay

import (
	"encoding/json"
	"errors"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
	"rsc.io/qr"
)

// Configs for other devices - see core/export.go for what a slot is.
//
// The order an export runs in, from the phone:
//
//	SaveServerChain("exit-chain")     what server + exit leaves by now
//	ExportSlot(k, pick) for each      finds the pick's exit that uploads,
//	                                  and points slot k at it
//	RestoreServerChain(saved)         server + exit back where it was
//	PersistServer                     all of it written to the server's file
//	ExportLinksJSON                   the links, and QRPNG for each

// ExportSlot points slot k at the pick's provider exit that uploads best
// from the server - the same search server + exit makes before it connects,
// which leaves the server's own exit-chain on the winner, so the caller puts
// that back afterwards (SaveServerChain / RestoreServerChain). Load must have
// run first, as for BestServerExit.
//
// The Result names the exit; its TookMs is the measured upload, in KB/s.
func (c *Client) ExportSlot(cfg *ServerConfig, apiPassword string, k int, code, ssUser, ssPassword,
	wsUser, wsPassword string, tries, timeoutMs int) (*Result, error) {

	if k < 1 || k > core.MaxExportSlots {
		return nil, errors.New("that slot is not one the server has")
	}
	best, err := c.BestServerExit(cfg, apiPassword, code, ssUser, ssPassword, wsUser, wsPassword, tries, timeoutMs)
	if err != nil {
		return nil, err
	}
	user, pass := ssUser, ssPassword
	if best.Provider == "windscribe" {
		user, pass = wsUser, wsPassword
	}
	if err := core.SetSlot(cfg.Domain, apiPassword, cfg.Pin, k, best.Addr, user, pass, best.File,
		15*time.Second); err != nil {
		return nil, err
	}
	return best, nil
}

// SaveServerChain is one of the server's chains as it is now, as JSON.
func SaveServerChain(cfg *ServerConfig, apiPassword, chain string, timeoutMs int) (string, error) {
	if cfg == nil || cfg.Domain == "" {
		return "", errors.New("no server configured")
	}
	return core.ChainJSON(cfg.Domain, apiPassword, cfg.Pin, chain, time.Duration(timeoutMs)*time.Millisecond)
}

// RestoreServerChain puts back what SaveServerChain read.
func RestoreServerChain(cfg *ServerConfig, apiPassword, chain, saved string, timeoutMs int) error {
	if cfg == nil || cfg.Domain == "" {
		return errors.New("no server configured")
	}
	return core.PutChainJSON(cfg.Domain, apiPassword, cfg.Pin, chain, saved, time.Duration(timeoutMs)*time.Millisecond)
}

// PersistServer writes what was set through the API to the server's own
// file, so the slots survive the server restarting.
func PersistServer(cfg *ServerConfig, apiPassword string, timeoutMs int) error {
	if cfg == nil || cfg.Domain == "" {
		return errors.New("no server configured")
	}
	return core.PersistServer(cfg.Domain, apiPassword, cfg.Pin, time.Duration(timeoutMs)*time.Millisecond)
}

// ExportLinksJSON makes the links for each slot.
//
//	host      the server, as the key has it
//	edge      a measured Cloudflare address for a server by name, or ""
//	exportID  the key's export id
//	slots     JSON: [{"k":0,"name":"Relay · server"}, {"k":1,"name":"Relay · Germany"}]
//	ss        JSON of the key's ss object, or "" - the server's own slot
//	          gets a Shadowsocks link too when it is given
//
// Returns JSON: [{"k","name","vless","vmess","ss"?}].
func ExportLinksJSON(host, edge, exportID, slots, ss string) (string, error) {
	var list []core.ExportSlot
	if err := json.Unmarshal([]byte(slots), &list); err != nil {
		return "", errors.New("the slots are not a list: " + err.Error())
	}
	srv := core.ExportServer{Host: host, Edge: edge, ID: exportID}
	if ss != "" {
		var s core.RelayKeySS
		if err := json.Unmarshal([]byte(ss), &s); err == nil && s.Password != "" && s.Port > 0 {
			srv.SS = &s
		}
	}
	links, err := core.ExportLinks(srv, list)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(links)
	return string(out), err
}

// QRPNG is text as a QR code, as PNG bytes - for a link to be read by the
// other device's camera. Medium error correction, which a link of a few
// hundred characters still fits comfortably; size is a hint in pixels and
// the image comes out at a whole number of pixels per module.
func QRPNG(text string, size int) ([]byte, error) {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return nil, err
	}
	n := code.Size + 8 // the quiet zone, four modules a side
	scale := size / n
	if scale < 2 {
		scale = 2
	}
	code.Scale = scale
	return code.PNG(), nil
}
