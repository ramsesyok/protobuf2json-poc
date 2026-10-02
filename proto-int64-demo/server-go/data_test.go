package main

import (
	"math"
	"testing"

	pb "demo/proto"

	"google.golang.org/protobuf/proto"
)

func TestBuildSimLogIsDeterministic(t *testing.T) {
	req := &pb.GetSimLogRequest{IncludeResult: true, ObjectCount: 20, EventsPerObject: 7}
	if !proto.Equal(buildSimLog(req), buildSimLog(req)) {
		t.Fatal("buildSimLog is not deterministic")
	}
}

func TestBuildSimLogCoversSpecialValues(t *testing.T) {
	sl := buildSimLog(&pb.GetSimLogRequest{ObjectCount: 10, EventsPerObject: 10})
	if sl.Result != nil {
		t.Fatal("result must be unset when include_result=false")
	}
	seen := map[int64]bool{}
	payloads := map[string]int{}
	bodies, commands, results := map[string]bool{}, map[string]bool{}, map[string]bool{}
	fromIDs, toIDs, execIDs := map[int64]bool{}, map[int64]bool{}, map[int64]bool{}
	for _, ol := range sl.Logs {
		seen[ol.Timestamp], seen[ol.ObjectId] = true, true
		for _, ev := range ol.Events {
			seen[ev.EventId], seen[ev.EventType] = true, true
			switch p := ev.Payload.(type) {
			case *pb.Event_CommEvent:
				payloads["comm"]++
				seen[p.CommEvent.FromId], seen[p.CommEvent.ToId] = true, true
				fromIDs[p.CommEvent.FromId], toIDs[p.CommEvent.ToId] = true, true
				bodies[p.CommEvent.Body] = true
			case *pb.Event_ExecEvent:
				payloads["exec"]++
				seen[p.ExecEvent.ExecId] = true
				execIDs[p.ExecEvent.ExecId] = true
				commands[p.ExecEvent.Command], results[p.ExecEvent.Result] = true, true
			case nil:
				payloads["none"]++
			}
		}
	}
	for _, v := range []int64{0, -1, 9007199254740993, math.MaxInt64, math.MinInt64} {
		if !seen[v] {
			t.Errorf("value %d not generated", v)
		}
	}
	// 各 string / int64 フィールドに全種類の特殊値が現れること
	for _, str := range stringSpecials {
		if !bodies[str] || !commands[str] || !results[str] {
			t.Errorf("string %q not in all of body/command/result", str)
		}
	}
	for _, v := range int64Specials {
		if !fromIDs[v] || !toIDs[v] || !execIDs[v] {
			t.Errorf("int64 %d not in all of from_id/to_id/exec_id", v)
		}
	}
	for _, k := range []string{"comm", "exec", "none"} {
		if payloads[k] == 0 {
			t.Errorf("payload %s not generated", k)
		}
	}
}
