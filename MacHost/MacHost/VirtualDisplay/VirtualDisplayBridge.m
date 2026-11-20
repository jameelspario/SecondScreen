#import "VirtualDisplayBridge.h"

@interface VirtualDisplayWrapper ()
@property (nonatomic, strong, nullable) CGVirtualDisplay *display;
@end

@implementation VirtualDisplayWrapper

+ (nullable instancetype)createWithName:(NSString *)name
                               vendorID:(uint32_t)vendorID
                              productID:(uint32_t)productID
                           serialNumber:(uint32_t)serialNumber
                      sizeInMillimeters:(CGSize)size
                          maxPixelsWide:(NSUInteger)maxW
                          maxPixelsHigh:(NSUInteger)maxH
                       terminationQueue:(dispatch_queue_t)queue
                     terminationHandler:(void (^)(void))handler {
    Class descriptorClass = NSClassFromString(@"CGVirtualDisplayDescriptor");
    if (!descriptorClass) return nil;

    CGVirtualDisplayDescriptor *descriptor = [[descriptorClass alloc] init];
    if (!descriptor) return nil;

    descriptor.name = name;
    descriptor.vendorID = vendorID;
    descriptor.productID = productID;
    descriptor.serialNumber = serialNumber;
    descriptor.sizeInMillimeters = size;
    descriptor.maxPixelsWide = maxW;
    descriptor.maxPixelsHigh = maxH;
    descriptor.dispatchQueue = queue;
    descriptor.terminationHandler = handler;

    Class displayClass = NSClassFromString(@"CGVirtualDisplay");
    if (!displayClass) return nil;

    CGVirtualDisplay *display = [[displayClass alloc] initWithDescriptor:descriptor];
    if (!display || display.displayID == 0) {
        return nil;
    }

    VirtualDisplayWrapper *wrapper = [[VirtualDisplayWrapper alloc] init];
    wrapper.display = display;
    return wrapper;
}

- (CGDirectDisplayID)displayID {
    return self.display ? self.display.displayID : 0;
}

- (BOOL)applyWidth:(NSUInteger)width
            height:(NSUInteger)height
       refreshRate:(double)refreshRate
             hiDPI:(BOOL)hiDPI {
    if (!self.display) return NO;

    Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
    Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
    if (!modeClass || !settingsClass) return NO;

    CGVirtualDisplayMode *mode = [[modeClass alloc] initWithWidth:width
                                                           height:height
                                                      refreshRate:refreshRate];
    if (!mode) return NO;

    CGVirtualDisplaySettings *settings = [[settingsClass alloc] init];
    if (!settings) return NO;

    settings.modes = @[mode];
    settings.hiDPI = hiDPI ? 1 : 0;
    return [self.display applySettings:settings];
}

- (void)invalidate {
    self.display = nil;
}

@end

