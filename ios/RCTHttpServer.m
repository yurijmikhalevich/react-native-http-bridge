#import "RCTHttpServer.h"
#import "React/RCTBridge.h"
#import "React/RCTLog.h"
#import "React/RCTEventDispatcher.h"

#import "GCDWebServer.h"
#import "GCDWebServerDataResponse.h"
#import "GCDWebServerDataRequest.h"
#import "GCDWebServerPrivate.h"
#include <stdlib.h>

@interface RCTHttpServer : NSObject <RCTBridgeModule> {
    GCDWebServer* _webServer;
    NSMutableDictionary* _completionBlocks;
}
@end

static RCTBridge *bridge;

@implementation RCTHttpServer

@synthesize bridge = _bridge;

- (id) init
{
  self = [super init];
  _completionBlocks = [[NSMutableDictionary alloc] init];
  return self;
}

RCT_EXPORT_MODULE();

- (void)initResponseReceivedFor:(GCDWebServer *)server forType:(NSString*)type {
  // Add handler.
  [server addHandlerWithMatchBlock:^GCDWebServerRequest * _Nullable(NSString * _Nonnull requestMethod, NSURL * _Nonnull requestURL, NSDictionary<NSString *,NSString *> * _Nonnull requestHeaders, NSString * _Nonnull urlPath, NSDictionary<NSString *,NSString *> * _Nonnull urlQuery) {
    // Figure out what type of request it is.
    NSString* contentType = requestHeaders[@"Content-Type"];
    if (contentType) {
      if ([contentType hasPrefix:@"application/x-www-form-urlencoded"]) {
        return [[GCDWebServerURLEncodedFormRequest alloc] initWithMethod:requestMethod url:requestURL headers:requestHeaders path:urlPath query:urlQuery];
      } else if ([contentType hasPrefix:@"multipart/form-data"]) {
        return [[GCDWebServerMultiPartFormRequest alloc] initWithMethod:requestMethod url:requestURL headers:requestHeaders path:urlPath query:urlQuery];
      }
    }
    return [[GCDWebServerDataRequest alloc] initWithMethod:requestMethod url:requestURL headers:requestHeaders path:urlPath query:urlQuery];
  } asyncProcessBlock:^(GCDWebServerRequest* request, GCDWebServerCompletionBlock completionBlock) {

        long long milliseconds = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
        int r = arc4random_uniform(1000000);
        NSString *requestId = [NSString stringWithFormat:@"%lld:%d", milliseconds, r];

         @synchronized (self) {
             [_completionBlocks setObject:completionBlock forKey:requestId];
         }

        @try {
          if ([request isKindOfClass:[GCDWebServerMultiPartFormRequest class]]) {
            GCDWebServerMultiPartFormRequest* multiPartRequest = (GCDWebServerMultiPartFormRequest*)request;

            // Arguments.
            NSMutableDictionary* args = [[NSMutableDictionary alloc] init];
            for (GCDWebServerMultiPartArgument* arg in multiPartRequest.arguments)
              [args setObject:arg.string forKey:arg.controlName];

            // Files.
            NSMutableDictionary* files = [[NSMutableDictionary alloc] init];
            for (GCDWebServerMultiPartFile* file in multiPartRequest.files)
            {
              // Generate a unique name and copy it to more permanent storage.
              NSString* filename = [[NSUUID UUID] UUIDString];
              NSArray* paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES);
              NSString* applicationSupportDirectory = [paths firstObject];
              NSString* destPath = [applicationSupportDirectory stringByAppendingPathComponent:filename];

              [[NSFileManager defaultManager] createDirectoryAtPath:applicationSupportDirectory withIntermediateDirectories:YES attributes:nil error:nil];
              [[NSFileManager defaultManager] moveItemAtPath:file.temporaryPath toPath:destPath error:nil];

              [files setObject:destPath forKey:file.controlName];
            }

            [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                                                         body:@{@"requestId": requestId,
                                                                @"type": request.method,
                                                                @"arguments": args,
                                                                @"files": files,
                                                                @"url": request.URL.relativeString}];
          }
          else if ([GCDWebServerTruncateHeaderValue(request.contentType) isEqualToString:@"application/json"]) {
                GCDWebServerDataRequest* dataRequest = (GCDWebServerDataRequest*)request;
                [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                                                             body:@{@"requestId": requestId,
                                                                    @"postData": dataRequest.jsonObject,
                                                                    @"type": type,
                                                                    @"url": request.URL.relativeString}];
            } else {
                [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                                                             body:@{@"requestId": requestId,
                                                                    @"type": type,
                                                                    @"url": request.URL.relativeString}];
            }
        } @catch (NSException *exception) {
            [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                                                         body:@{@"requestId": requestId,
                                                                @"type": type,
                                                                @"url": request.URL.relativeString}];
        }
    }];
}

RCT_EXPORT_METHOD(start:(NSInteger) port
                  serviceName:(NSString *) serviceName)
{
    RCTLogInfo(@"Running HTTP bridge server: %ld", port);
    NSMutableDictionary *_requestResponses = [[NSMutableDictionary alloc] init];
    
    dispatch_sync(dispatch_get_main_queue(), ^{
        _webServer = [[GCDWebServer alloc] init];
        
        [self initResponseReceivedFor:_webServer forType:@"POST"];
        [self initResponseReceivedFor:_webServer forType:@"PUT"];
        [self initResponseReceivedFor:_webServer forType:@"GET"];
        [self initResponseReceivedFor:_webServer forType:@"DELETE"];
        
        [_webServer startWithPort:port bonjourName:serviceName];
    });
}

RCT_EXPORT_METHOD(stop)
{
    RCTLogInfo(@"Stopping HTTP bridge server");
    
    if (_webServer != nil) {
        if (_webServer.isRunning)
          [_webServer stop];
        [_webServer removeAllHandlers];
        _webServer = nil;
    }
}

RCT_EXPORT_METHOD(respond: (NSString *) requestId
                  code: (NSInteger) code
                  type: (NSString *) type
                  body: (NSString *) body)
{
    NSData* data = [body dataUsingEncoding:NSUTF8StringEncoding];
    GCDWebServerDataResponse* requestResponse = [[GCDWebServerDataResponse alloc] initWithData:data contentType:type];
    requestResponse.statusCode = code;

    GCDWebServerCompletionBlock completionBlock = nil;
    @synchronized (self) {
        completionBlock = [_completionBlocks objectForKey:requestId];
        [_completionBlocks removeObjectForKey:requestId];
    }

    if (completionBlock)
        completionBlock(requestResponse);
}

@end
