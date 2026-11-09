package relay

import (
	"encoding/json"
	"errors"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// Sharing the connection with other devices on the same network, across the
// bridge. What it does and why is lanproxy.go's story in core; this is only
// the three calls the screen needs.

type lanState struct {
	On      bool     `json:"on"`
	Port    int      `json:"port"`
	Addrs   []string `json:"addrs"`
	Clients int      `json:"clients"`
}

// StartLANShare starts the proxy on port and answers with where to point the
// other device: {"addrs":["192.168.1.23", ...],"port":8877}. An empty
// password leaves it open to anyone on the network; with one, HTTP clients
// give it as the proxy password for the user "relay".
//
// An error when no tunnel is up - there would be nothing to share - or when
// the port is taken. Already sharing: the old one is replaced, so changing
// the port or the password is the same call.
func (c *Client) StartLANShare(port int, password string) (string, error) {
	if port < 1 || port > 65535 {
		return "", errors.New("that is not a port")
	}
	c.mu.Lock()
	t, old := c.tunnel, c.lan
	c.lan = nil
	c.mu.Unlock()
	if old != nil {
		old.Close()
	}
	if t == nil {
		return "", errors.New("no tunnel is up")
	}
	be := t.LANBackend()
	if be == nil {
		return "", errors.New("sharing needs a tunnel, which this platform does not have")
	}
	p := core.NewLANProxy(be, password)
	if err := p.Start(port); err != nil {
		return "", err
	}

	c.mu.Lock()
	if c.tunnel != t {
		// The tunnel went away while the port was being opened.
		c.mu.Unlock()
		p.Close()
		return "", errors.New("the tunnel stopped")
	}
	c.lan = p
	c.mu.Unlock()

	out, _ := json.Marshal(struct {
		Addrs []string `json:"addrs"`
		Port  int      `json:"port"`
	}{core.LocalAddrs(), p.Port()})
	return string(out), nil
}

// StopLANShare stops sharing. Safe when it is not on.
func (c *Client) StopLANShare() {
	c.mu.Lock()
	p := c.lan
	c.lan = nil
	c.mu.Unlock()
	if p != nil {
		p.Close()
	}
}

// LANShareJSON is {"on":bool,"port":n,"addrs":[...],"clients":n} - cheap
// enough to ask every few seconds while the screen is open.
func (c *Client) LANShareJSON() string {
	c.mu.Lock()
	p := c.lan
	c.mu.Unlock()
	st := lanState{Addrs: []string{}}
	if p != nil {
		st.On, st.Port, st.Clients = true, p.Port(), p.Clients()
		st.Addrs = core.LocalAddrs()
	}
	b, _ := json.Marshal(st)
	return string(b)
}
