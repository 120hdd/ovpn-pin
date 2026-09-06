//go:build !android && !linux

package core

// Nothing to protect from: a desktop is not routing its own traffic into a
// tun this process is holding, so a socket to the exit is an ordinary socket.
func SetSocketProtector(func(fd int) bool) {}
