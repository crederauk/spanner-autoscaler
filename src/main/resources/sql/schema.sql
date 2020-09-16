CREATE TABLE Instances (
  InstanceId   string(36),
  ProjectId    string(MAX),
  InstanceName string(MAX)
) PRIMARY KEY (InstanceId);
CREATE INDEX Instance__Idx ON Instances (ProjectId, InstanceId);

CREATE TABLE PollingEvents (
  InstanceId     string(36),
  PollingEventId string(36),
  EventTimestamp timestamp,
  Metrics        string(MAX)
) PRIMARY KEY (InstanceId,PollingEventId),
  INTERLEAVE IN PARENT Instances ON
DELETE CASCADE;

CREATE INDEX PollingEvents__Idx ON PollingEvents (InstanceId, EventTimestamp DESC);

CREATE TABLE ScalingEvents (
  InstanceId     string(36),
  ScalingEventId string(36),
  EventTimestamp timestamp,
  Action         string(MAX),
  NodesBefore    int64,
  NodesAfter     int64
) PRIMARY KEY (InstanceId,ScalingEventId),
  INTERLEAVE IN PARENT Instances ON
DELETE CASCADE;

CREATE INDEX ScalingEvents__Idx ON ScalingEvents (InstanceId, EventTimestamp DESC);

CREATE TABLE InstanceLocks (
  InstanceId  string(36),
  LockId      string(36),
  LockTimeout timestamp
) PRIMARY KEY (InstanceId,LockId),
  INTERLEAVE IN PARENT Instances ON
DELETE CASCADE;

CREATE INDEX InstanceLocks__Idx ON InstanceLocks (InstanceId, LockTimeout DESC);

