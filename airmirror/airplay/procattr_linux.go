//go:build linux

package airplay

import "syscall"

// childProcAttr kills the capture child if doubletake dies.
func childProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
}
