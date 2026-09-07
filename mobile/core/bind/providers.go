package relay

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// Signing in, and fetching what a provider publishes.
//
// The window has always had these buttons; on a phone they answered "not
// built yet". Everything they need is here rather than in Kotlin because it
// is all HTTP and parsing, and because the three ways of reaching an API from
// a filtered line already exist in core - through whatever is carrying
// traffic, straight at it, or at an address found over DNS-over-HTTPS with
// the name still on the handshake.

// SurfsharkServersJSON fetches the published cluster list and writes what is
// missing into the Surfshark inbox.
//
// No sign-in: the list is public, and the account is a service credential the
// exits themselves check.
func SurfsharkServersJSON(timeoutMs int) (out string) {
	defer said(&out)

	ctx, cancel := context.WithTimeout(context.Background(), ms(timeoutMs))
	defer cancel()

	folder, total, countries, added, kept, err := core.WriteSurfsharkConfigs(
		ctx, core.ConfigsDir(), ms(timeoutMs))
	if err != nil {
		return refused(err.Error())
	}
	return encode(struct {
		OK        bool   `json:"ok"`
		Folder    string `json:"folder"`
		Total     int    `json:"total"`
		Countries int    `json:"countries"`
		Added     int    `json:"added"`
		Kept      int    `json:"kept"`
	}{true, folder, total, countries, added, kept})
}

// WindscribeServersJSON does the same for Windscribe, into its own inbox.
func WindscribeServersJSON(freeOnly bool, timeoutMs int) (out string) {
	defer said(&out)

	ctx, cancel := context.WithTimeout(context.Background(), ms(timeoutMs))
	defer cancel()

	folder, written, countries, total, err := core.WriteWindscribeConfigs(
		ctx, core.WindscribeDir(), freeOnly, ms(timeoutMs))
	if err != nil {
		return refused(err.Error())
	}
	return encode(struct {
		OK        bool   `json:"ok"`
		Folder    string `json:"folder"`
		Written   int    `json:"written"`
		Countries int    `json:"countries"`
		Total     int    `json:"total"`
	}{true, folder, written, countries, total})
}

// WindscribeBeginJSON asks for a login token and whatever puzzle comes with
// it, in the shape the page draws.
func WindscribeBeginJSON(username, password string, timeoutMs int) (out string) {
	defer said(&out)

	ctx, cancel := context.WithTimeout(context.Background(), ms(timeoutMs))
	defer cancel()

	token, captcha, err := core.BeginLogin(ctx, username, password, ms(timeoutMs))
	if err != nil {
		return apiRefusal(err)
	}
	return encode(struct {
		OK      bool          `json:"ok"`
		Token   string        `json:"token"`
		Captcha *core.Captcha `json:"captcha"`
	}{true, token, captcha})
}

// WindscribeFinishJSON is the login itself, with the solved puzzle attached.
//
// The trails arrive as comma-separated integers, because gomobile carries no
// slices. They are the path the piece was dragged along, which is the half of
// the puzzle actually about being human - where it stopped is easy.
func WindscribeFinishJSON(
	username, password, token, solution, trailX, trailY, code2fa string,
	timeoutMs int,
) (out string) {
	defer said(&out)

	ctx, cancel := context.WithTimeout(context.Background(), ms(timeoutMs))
	defer cancel()

	session, err := core.FinishLogin(ctx, username, password, token, solution,
		numbers(trailX), numbers(trailY), code2fa, ms(timeoutMs))
	if err != nil {
		return apiRefusal(err)
	}
	return encode(struct {
		OK       bool   `json:"ok"`
		Session  string `json:"session"`
		Username string `json:"username"`
		Premium  bool   `json:"premium"`
	}{true, session.Hash, session.Username, session.Premium})
}

// WindscribeCredentialsJSON turns a session into the credential the proxy
// takes. No puzzle on this one, which is why the session is worth keeping.
func WindscribeCredentialsJSON(session string, timeoutMs int) (out string) {
	defer said(&out)

	ctx, cancel := context.WithTimeout(context.Background(), ms(timeoutMs))
	defer cancel()

	user, password, err := core.ProxyCredentials(ctx, session, ms(timeoutMs))
	if err != nil {
		return apiRefusal(err)
	}
	// These go to the phone's keystore and to the credential file, and
	// nowhere near the page.
	return encode(struct {
		OK       bool   `json:"ok"`
		User     string `json:"user"`
		Password string `json:"password"`
	}{true, user, password})
}

// apiRefusal keeps the provider's own code, which the page reads: 707 is a
// rate limit worth waiting out and 708 is a puzzle that has to be solved
// again, and neither is a wrong password.
func apiRefusal(err error) string {
	var api *core.ApiError
	if errors.As(err, &api) {
		return encode(struct {
			OK    bool   `json:"ok"`
			Error string `json:"error"`
			Code  int    `json:"code"`
			Why   string `json:"why"`
		}{false, api.Message, api.Code, api.Description})
	}
	return refused(err.Error())
}

func encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return refused("could not describe that answer")
	}
	return string(b)
}

// numbers reads the comma-separated integers a trail crosses as.
func numbers(text string) []int {
	var out []int
	for _, part := range strings.Split(text, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if n, err := strconv.Atoi(part); err == nil {
			out = append(out, n)
		}
	}
	return out
}
