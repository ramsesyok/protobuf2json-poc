package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"

	pb "demo/proto"

	"google.golang.org/grpc"
)

type simServer struct {
	pb.UnimplementedSimServiceServer
}

func (s *simServer) GetSimLog(_ context.Context, req *pb.GetSimLogRequest) (*pb.SimLog, error) {
	log.Printf("GetSimLog include_result=%v object_count=%d events_per_object=%d",
		req.GetIncludeResult(), req.GetObjectCount(), req.GetEventsPerObject())
	return buildSimLog(req), nil
}

func (s *simServer) StreamObjectLogs(req *pb.GetSimLogRequest, stream grpc.ServerStreamingServer[pb.ObjectLog]) error {
	log.Printf("StreamObjectLogs object_count=%d events_per_object=%d",
		req.GetObjectCount(), req.GetEventsPerObject())
	for i := 0; i < int(req.GetObjectCount()); i++ {
		if err := stream.Send(buildObjectLog(i, int(req.GetEventsPerObject()))); err != nil {
			return err
		}
	}
	return nil
}

func main() {
	port := flag.Int("port", 50051, "listen port")
	flag.Parse()

	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", *port))
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	s := grpc.NewServer()
	pb.RegisterSimServiceServer(s, &simServer{})
	log.Printf("SimService gRPC server listening on :%d", *port)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("serve: %v", err)
	}
}
