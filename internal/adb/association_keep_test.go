package adb

// 测试用途：验证保留前的查询只使用固定list参数，不创建或删除任何关联。

import (
	"context"
	"strconv"
	"testing"
)

func TestFindCameraAssociationReadsOnlyExactMarker(t *testing.T) {
	for _, present := range []bool{false, true} {
		for _, user := range []int{-1, 0} {
			f := &cameraADBExecutor{associated: present}
			got, err := NewClient("adb", f).FindCameraAssociation(context.Background(), "FIRST", user)
			wantUser := user
			if user < 0 {
				wantUser = 10
			}
			if err != nil || got.UserID != wantUser || (got.ID == 90) != present {
				t.Fatal(got, err)
			}
			lists := 0
			for _, args := range f.calls {
				if len(args) > 5 && args[4] == "companiondevice" {
					lists++
					assertArgs(t, args, "-s", "FIRST", "shell", "cmd", "companiondevice", "list", strconv.Itoa(wantUser))
				}
			}
			if lists != 1 {
				t.Fatal(f.calls)
			}
		}
	}
}
