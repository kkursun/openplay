//go:build !linux

package airplay

import "syscall"

// Pdeathsig is Linux-only; other platforms rely on the caller stopping the child.
func childProcAttr() *syscall.SysProcAttr {
	return nil
}
